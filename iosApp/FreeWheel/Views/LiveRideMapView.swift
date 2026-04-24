import SwiftUI
import MapKit
import FreeWheelCore

/// MapKit-based live-ride map. Shows the platform blue user-dot plus a
/// speed-colored polyline that grows as the ride proceeds. In follow mode,
/// the camera tracks the user; user gestures break follow mode and surface
/// the recenter FAB (owned by the parent screen).
struct LiveRideMapView: UIViewRepresentable {
    let routePoints: [RoutePoint]
    let speedRange: SpeedRange?
    @Binding var followMode: Bool
    var chargers: [ChargingStation] = []
    var onChargerTap: (ChargingStation) -> Void = { _ in }
    var onCameraIdle: (CLLocationCoordinate2D) -> Void = { _ in }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    func makeUIView(context: Context) -> MKMapView {
        let mapView = MKMapView()
        mapView.delegate = context.coordinator
        mapView.showsUserLocation = true
        mapView.isRotateEnabled = false
        mapView.showsCompass = false
        mapView.userTrackingMode = followMode ? .follow : .none
        return mapView
    }

    func updateUIView(_ mapView: MKMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self

        if coordinator.lastRouteCount != routePoints.count {
            coordinator.lastRouteCount = routePoints.count
            rebuildPolyline(on: mapView, coordinator: coordinator)
        }

        let stationIds = Set(chargers.map { $0.id })
        if coordinator.displayedStationIds != stationIds {
            coordinator.displayedStationIds = stationIds
            rebuildChargerAnnotations(on: mapView, coordinator: coordinator)
        }

        // Sync follow-mode binding → MapKit tracking mode, without fighting the
        // user's pan gestures (delegate resets the binding to false on pan).
        let desired: MKUserTrackingMode = followMode ? .follow : .none
        if mapView.userTrackingMode != desired {
            mapView.setUserTrackingMode(desired, animated: true)
        }
    }

    private func rebuildChargerAnnotations(on mapView: MKMapView, coordinator: Coordinator) {
        for ann in mapView.annotations where ann is ChargingStationAnnotation {
            mapView.removeAnnotation(ann)
        }
        for station in chargers {
            mapView.addAnnotation(ChargingStationAnnotation(station: station))
        }
    }

    private func rebuildPolyline(on mapView: MKMapView, coordinator: Coordinator) {
        mapView.removeOverlays(mapView.overlays)
        for ann in mapView.annotations where ann is RouteStartAnnotation {
            mapView.removeAnnotation(ann)
        }
        guard routePoints.count >= 2 else { return }

        let coords = routePoints.map {
            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
        }
        let polyline = MKPolyline(coordinates: coords, count: coords.count)
        let (colors, locations) = buildGradient(coords: coords)
        coordinator.polylineColors = colors
        coordinator.polylineLocations = locations
        mapView.addOverlay(polyline)

        if let first = coords.first {
            mapView.addAnnotation(RouteStartAnnotation(coordinate: first))
        }
    }

    private func buildGradient(coords: [CLLocationCoordinate2D]) -> ([UIColor], [CGFloat]) {
        let minSpeed = speedRange?.min ?? 0
        let maxSpeed = speedRange?.max ?? 0
        let range = maxSpeed - minSpeed

        var cumDist = [0.0]
        for i in 1..<coords.count {
            let prev = CLLocation(latitude: coords[i - 1].latitude, longitude: coords[i - 1].longitude)
            let curr = CLLocation(latitude: coords[i].latitude, longitude: coords[i].longitude)
            cumDist.append(cumDist[i - 1] + prev.distance(from: curr))
        }
        let total = cumDist.last ?? 1

        var colors: [UIColor] = []
        var locations: [CGFloat] = []
        for i in 0..<routePoints.count {
            let fraction = range > 0 ? (routePoints[i].speedKmh - minSpeed) / range : 0
            colors.append(speedColor(fraction: fraction))
            locations.append(CGFloat(total > 0 ? cumDist[i] / total : 0))
        }
        return (colors, locations)
    }

    /// Green (0.0) → Yellow (0.5) → Red (1.0). Matches trip detail.
    private func speedColor(fraction: Double) -> UIColor {
        let clamped = min(max(fraction, 0), 1)
        if clamped < 0.5 {
            let t = clamped * 2
            return UIColor(red: t, green: 1.0, blue: 0.0, alpha: 1.0)
        } else {
            let t = (clamped - 0.5) * 2
            return UIColor(red: 1.0, green: 1.0 - t, blue: 0.0, alpha: 1.0)
        }
    }

    // MARK: - Annotations

    class RouteStartAnnotation: MKPointAnnotation {
        init(coordinate: CLLocationCoordinate2D) {
            super.init()
            self.coordinate = coordinate
        }
    }

    class ChargingStationAnnotation: MKPointAnnotation {
        let station: ChargingStation
        init(station: ChargingStation) {
            self.station = station
            super.init()
            self.coordinate = CLLocationCoordinate2D(latitude: station.latitude, longitude: station.longitude)
            self.title = station.name
            self.subtitle = station.address
        }
    }

    // MARK: - Coordinator

    class Coordinator: NSObject, MKMapViewDelegate {
        var parent: LiveRideMapView
        var lastRouteCount = 0
        var polylineColors: [UIColor] = []
        var polylineLocations: [CGFloat] = []
        var displayedStationIds: Set<String> = []

        init(parent: LiveRideMapView) { self.parent = parent }

        func mapView(_ mapView: MKMapView, rendererFor overlay: any MKOverlay) -> MKOverlayRenderer {
            guard let polyline = overlay as? MKPolyline else {
                return MKOverlayRenderer(overlay: overlay)
            }
            let renderer = MKGradientPolylineRenderer(polyline: polyline)
            renderer.setColors(polylineColors, locations: polylineLocations)
            renderer.lineWidth = 5
            renderer.lineCap = .round
            renderer.lineJoin = .round
            return renderer
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: any MKAnnotation) -> MKAnnotationView? {
            if let start = annotation as? RouteStartAnnotation {
                let id = "live-start"
                let view = mapView.dequeueReusableAnnotationView(withIdentifier: id)
                    ?? MKAnnotationView(annotation: start, reuseIdentifier: id)
                view.annotation = start
                let size: CGFloat = 12
                let r = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))
                view.image = r.image { ctx in
                    UIColor.systemGreen.setFill()
                    ctx.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: size, height: size))
                    UIColor.white.setStroke()
                    ctx.cgContext.setLineWidth(2)
                    ctx.cgContext.strokeEllipse(in: CGRect(x: 1, y: 1, width: size - 2, height: size - 2))
                }
                return view
            }
            if let charger = annotation as? ChargingStationAnnotation {
                let id = "charger-pin"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: id) as? MKMarkerAnnotationView)
                    ?? MKMarkerAnnotationView(annotation: charger, reuseIdentifier: id)
                view.annotation = charger
                view.markerTintColor = .systemBlue
                view.glyphImage = UIImage(systemName: "bolt.fill")
                view.canShowCallout = true
                return view
            }
            return nil
        }

        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            if let charger = view.annotation as? ChargingStationAnnotation {
                parent.onChargerTap(charger.station)
            }
        }

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            parent.onCameraIdle(mapView.centerCoordinate)
        }

        /// MapKit auto-reverts tracking mode to .none on any user pan/zoom gesture.
        /// Mirror that back into the SwiftUI binding so the recenter FAB appears.
        func mapView(_ mapView: MKMapView, didChange mode: MKUserTrackingMode, animated: Bool) {
            if mode == .none, parent.followMode {
                DispatchQueue.main.async { self.parent.followMode = false }
            }
        }
    }
}

/// Top-level Map tab screen. Always shows the map (with the platform user-dot)
/// so the user can see their current location even when no wheel is connected.
/// The telemetry overlay only appears while a ride is being logged.
struct LiveRideMapScreen: View {
    @EnvironmentObject var wheelManager: WheelManager
    @State private var followMode: Bool = true
    @State private var selectedCharger: ChargingStation?

    var body: some View {
        ZStack(alignment: .top) {
            LiveRideMapView(
                routePoints: wheelManager.liveRoutePoints,
                speedRange: wheelManager.liveRouteSpeedRange,
                followMode: $followMode,
                chargers: wheelManager.nearbyChargers,
                onChargerTap: { selectedCharger = $0 },
                onCameraIdle: { coord in
                    wheelManager.refreshChargers(latitude: coord.latitude, longitude: coord.longitude)
                }
            )
            .ignoresSafeArea(edges: .bottom)

            if wheelManager.isLogging {
                LiveRideOverlayCard()
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
            }
        }
        .sheet(item: $selectedCharger) { station in
            ChargingStationSheet(station: station)
                .presentationDetents([.fraction(0.35), .medium])
        }
        .overlay(alignment: .bottomTrailing) {
            if !followMode {
                Button {
                    followMode = true
                } label: {
                    Image(systemName: "location.fill")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 48, height: 48)
                        .background(Color.accentColor, in: Circle())
                        .shadow(radius: 4, y: 2)
                }
                .padding(.trailing, 16)
                .padding(.bottom, 24)
                .transition(.opacity.combined(with: .scale))
            }
        }
        .animation(.easeInOut(duration: 0.18), value: followMode)
        .navigationTitle("Map")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            // Keep GPS flowing while the Map tab is visible so the user-dot
            // appears even when no wheel is connected.
            wheelManager.locationManager.startTracking()
        }
        .onDisappear {
            // Release the GPS subscription if no other component still needs it.
            if !wheelManager.connectionState.isConnected {
                wheelManager.locationManager.stopTracking()
            }
        }
    }
}

extension ChargingStation: @retroactive Identifiable {}

private struct ChargingStationSheet: View {
    let station: ChargingStation

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(station.name)
                .font(.title3.weight(.semibold))
            if let address = station.address {
                Text(address).font(.subheadline).foregroundStyle(.secondary)
            }
            if let op = station.operator_ {
                Text("Operator: \(op)").font(.subheadline)
            }
            let connectors = station.connectors.map { $0.displayName }.joined(separator: ", ")
            Text("Connectors: \(connectors)").font(.subheadline)
            if let distanceBoxed = station.distanceKm {
                Text(String(format: "~%.1f km away", distanceBoxed.doubleValue))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Floating glass-style overlay: speed hero + battery, PWM, distance, time.
private struct LiveRideOverlayCard: View {
    @EnvironmentObject var wheelManager: WheelManager

    private var speedDisplay: Double {
        let kmh = wheelManager.telemetry?.speedKmh ?? 0
        return DisplayUtils.shared.convertSpeed(kmh: kmh, useMph: wheelManager.useMph)
    }

    private var speedUnit: String { wheelManager.useMph ? "mph" : "km/h" }

    private var distanceDisplay: String {
        let km = wheelManager.liveRideDistanceKm
        let value = wheelManager.useMph ? ByteUtils.shared.kmToMiles(km: km) : km
        return String(format: "%.2f", value) + " " + (wheelManager.useMph ? "mi" : "km")
    }

    private var elapsedDisplay: String {
        let seconds = Int(wheelManager.liveRideElapsedSeconds)
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        let s = seconds % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, s) }
        return String(format: "%d:%02d", m, s)
    }

    var body: some View {
        VStack(spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(String(format: "%.1f", speedDisplay))
                    .font(.system(size: 44, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                Text(speedUnit)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 18) {
                stat(label: "Battery", value: String(format: "%.0f%%", wheelManager.telemetry?.batteryLevel ?? 0))
                stat(label: "PWM", value: String(format: "%.0f%%", wheelManager.telemetry?.pwmPercent ?? 0))
                stat(label: "Distance", value: distanceDisplay)
                stat(label: "Time", value: elapsedDisplay)
            }
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .shadow(color: .black.opacity(0.1), radius: 6, y: 2)
    }

    private func stat(label: String, value: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.footnote.weight(.semibold))
                .monospacedDigit()
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }
}
