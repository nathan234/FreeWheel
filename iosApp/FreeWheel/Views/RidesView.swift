import SwiftUI
import FreeWheelCore

// CROSS-PLATFORM SYNC: This view mirrors freewheel/.../compose/screens/RidesScreen.kt.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Title header (or selection count in select mode)
//  2. Empty state with icon and message
//  3. Ride list with swipe-to-delete (disabled in select mode)
//  4. Ride row: friendly date, duration | distance, max speed | avg speed
//  5. Ride row (optional): power | energy stats
//  6. Share button per ride (iOS: ShareLink; Android: share Intent)
//  7. Bottom toolbar with "Merge Rides" button (select mode, 2+ selected)
//  Note: Android navigates to TripDetailScreen on tap; iOS uses NavigationLink
//  Note: Tap "Select" to enter select mode for merging

struct RidesView: View {
    @EnvironmentObject var wheelManager: WheelManager

    @State private var isSelecting = false
    @State private var selectedIds: Set<String> = []
    @State private var showMergeConfirm = false

    var body: some View {
        Group {
            if wheelManager.rideStore.rides.isEmpty {
                emptyState
            } else {
                rideList
            }
        }
        .navigationTitle(isSelecting ? "\(selectedIds.count) \(RidesLabels.shared.SELECTED_SUFFIX)" : RidesLabels.shared.TITLE)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                if isSelecting {
                    Button(CommonLabels.shared.CANCEL) {
                        isSelecting = false
                        selectedIds = []
                    }
                }
            }
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                if wheelManager.rideStore.rides.count >= 2 {
                    Button(isSelecting ? CommonLabels.shared.DONE : CommonLabels.shared.SELECT) {
                        isSelecting.toggle()
                        if !isSelecting { selectedIds = [] }
                    }
                }
                if wheelManager.connectionState.isConnected && !isSelecting {
                    Button(action: toggleLogging) {
                        Image(systemName: wheelManager.isLogging ? "stop.circle.fill" : "record.circle")
                            .foregroundColor(wheelManager.isLogging ? .red : .primary)
                    }
                }
            }
            ToolbarItem(placement: .bottomBar) {
                if isSelecting && selectedIds.count >= 2 {
                    Button {
                        showMergeConfirm = true
                    } label: {
                        Label(RidesLabels.shared.MERGE_RIDES, systemImage: "arrow.triangle.merge")
                    }
                }
            }
        }
        .alert(RidesLabels.shared.MERGE_CONFIRM_TITLE, isPresented: $showMergeConfirm) {
            Button(CommonLabels.shared.CANCEL, role: .cancel) {}
            Button(RidesLabels.shared.MERGE_RIDES) {
                let ids = Array(selectedIds)
                _ = wheelManager.stitchRides(ids)
                isSelecting = false
                selectedIds = []
            }
        } message: {
            Text(RidesLabels.shared.MERGE_CONFIRM_MESSAGE)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "road.lanes")
                .font(.system(size: 60))
                .foregroundColor(.secondary)
            Text(RidesLabels.shared.EMPTY_TITLE)
                .font(.title2)
                .fontWeight(.medium)
            Text(RidesLabels.shared.EMPTY_SUBTITLE)
                .font(.body)
                .foregroundColor(.secondary)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private var rideList: some View {
        List {
            ForEach(wheelManager.rideStore.rides) { ride in
                if isSelecting {
                    selectableRideRow(ride)
                } else {
                    rideRow(ride)
                }
            }
            .onDelete { offsets in
                if !isSelecting {
                    wheelManager.rideStore.deleteRide(at: offsets)
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private func selectableRideRow(_ ride: RideMetadata) -> some View {
        Button {
            if selectedIds.contains(ride.id) {
                selectedIds.remove(ride.id)
            } else {
                selectedIds.insert(ride.id)
            }
        } label: {
            HStack {
                Image(systemName: selectedIds.contains(ride.id) ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(selectedIds.contains(ride.id) ? .accentColor : .secondary)
                rideContent(ride)
            }
        }
        .buttonStyle(.plain)
    }

    private func rideRow(_ ride: RideMetadata) -> some View {
        NavigationLink(destination: TripDetailView(ride: ride)) {
            HStack {
                rideContent(ride)

                Spacer()

                ShareLink(item: wheelManager.rideStore.fileURL(for: ride)) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.caption)
                }
                .buttonStyle(.borderless)
            }
            .padding(.vertical, 2)
        }
    }

    private func rideContent(_ ride: RideMetadata) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            // Friendly date title
            Text(PlatformDateFormatter.shared.formatFriendlyDate(epochMs: Int64(ride.startDate.timeIntervalSince1970 * 1000)))
                .font(.headline)

            // Line 1: Duration + Distance
            Text("\(DisplayUtils.shared.formatDurationCompact(seconds: Int32(ride.duration)))  |  \(DisplayUtils.shared.formatDistance(km: ride.distance, useMph: wheelManager.useMph, decimals: 2))")
                .font(.subheadline)
                .foregroundColor(.secondary)

            // Line 2: Max speed + Avg speed
            Text("\(DisplayUtils.shared.formatSpeed(kmh: ride.maxSpeed, useMph: wheelManager.useMph, decimals: 0)) max  |  \(DisplayUtils.shared.formatSpeed(kmh: ride.avgSpeed, useMph: wheelManager.useMph, decimals: 0)) avg")
                .font(.caption)
                .foregroundColor(.secondary)

            // Line 3: Power + Energy (if data exists)
            if ride.maxPower > 0 || ride.consumptionWhPerKm > 0 {
                let parts = powerEnergyParts(ride)
                Text(parts)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
    }

    private func powerEnergyParts(_ ride: RideMetadata) -> String {
        var parts: [String] = []
        if ride.maxPower > 0 {
            parts.append("\(Int(ride.maxPower)) W max")
        }
        if ride.consumptionWhPerKm > 0 {
            parts.append(DisplayUtils.shared.formatEnergyConsumption(whPerKm: ride.consumptionWhPerKm, useMph: wheelManager.useMph, decimals: 1))
        }
        return parts.joined(separator: "  |  ")
    }

    private func toggleLogging() {
        if wheelManager.isLogging {
            wheelManager.stopLogging()
        } else {
            wheelManager.startLogging()
        }
    }

}

#Preview {
    NavigationStack {
        RidesView()
            .environmentObject(WheelManager())
    }
}
