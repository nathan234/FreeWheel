import SwiftUI
import WheelLogCore

/// Maps KMP SpeedDisplayMode to Swift-friendly Int raw values for UserDefaults persistence.
extension WheelLogCore.SpeedDisplayMode {
    static func fromRawValue(_ rawValue: Int) -> WheelLogCore.SpeedDisplayMode? {
        switch rawValue {
        case 0: return .wheel
        case 1: return .gps
        case 2: return .both
        default: return nil
        }
    }

    var rawValue: Int {
        switch self {
        case .wheel: return 0
        case .gps: return 1
        case .both: return 2
        default: return 0
        }
    }
}

typealias SpeedDisplayMode = WheelLogCore.SpeedDisplayMode

private let gpsCyan = Color(red: 0, green: 0.737, blue: 0.831) // #00BCD4

struct SpeedGaugeView: View {
    let speed: Double
    let maxSpeed: Double
    let unitLabel: String
    var gpsSpeed: Double = 0
    var mode: SpeedDisplayMode = .wheel

    init(speed: Double, maxSpeed: Double = 50.0, unitLabel: String = "km/h",
         gpsSpeed: Double = 0, mode: SpeedDisplayMode = .wheel) {
        self.speed = speed
        self.maxSpeed = maxSpeed
        self.unitLabel = unitLabel
        self.gpsSpeed = gpsSpeed
        self.mode = mode
    }

    private var arcSpeed: Double {
        switch mode {
        case .wheel, .both: return speed
        case .gps: return gpsSpeed
        default: return speed
        }
    }

    private var speedProgress: Double {
        min(arcSpeed / maxSpeed, 1.0)
    }

    private var arcColor: Color {
        switch mode {
        case .gps:
            return gpsCyan
        case .wheel, .both:
            if speedProgress < 0.5 { return .green }
            if speedProgress < 0.75 { return .orange }
            return .red
        default:
            return .green
        }
    }

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let lineWidth: CGFloat = size * 0.08
            let radius = (size - lineWidth) / 2

            ZStack {
                // Background arc
                Circle()
                    .trim(from: 0.15, to: 0.85)
                    .stroke(
                        Color.gray.opacity(0.2),
                        style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                    )
                    .rotationEffect(.degrees(90))

                // Progress arc
                Circle()
                    .trim(from: 0.15, to: 0.15 + (0.7 * speedProgress))
                    .stroke(
                        arcColor,
                        style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                    )
                    .rotationEffect(.degrees(90))
                    .animation(.spring(response: 0.3), value: speedProgress)

                // Speed labels around the arc
                ForEach(Array(stride(from: 0, through: Int(maxSpeed), by: 10)), id: \.self) { tickSpeed in
                    let tickProgress = Double(tickSpeed) / maxSpeed
                    let angle = Angle.degrees(-144 + (288 * tickProgress))

                    Text("\(tickSpeed)")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .position(
                            x: size / 2 + (radius - lineWidth - 10) * cos(CGFloat(angle.radians - .pi / 2)),
                            y: size / 2 + (radius - lineWidth - 10) * sin(CGFloat(angle.radians - .pi / 2))
                        )
                }

                // Center content
                VStack(spacing: 4) {
                    switch mode {
                    case .wheel:
                        Text(String(format: "%.1f", speed))
                            .font(.system(size: size * 0.18, weight: .bold, design: .rounded))
                            .foregroundColor(arcColor)
                    case .gps:
                        Text(gpsSpeed > 0 ? String(format: "%.1f", gpsSpeed) : "\u{2014}")
                            .font(.system(size: size * 0.18, weight: .bold, design: .rounded))
                            .foregroundColor(gpsCyan)
                    case .both:
                        Text(String(format: "%.1f", speed))
                            .font(.system(size: size * 0.18, weight: .bold, design: .rounded))
                            .foregroundColor(arcColor)
                        Text(gpsSpeed > 0 ? String(format: "GPS %.1f", gpsSpeed) : "GPS \u{2014}")
                            .font(.system(size: size * 0.06, weight: .medium))
                            .foregroundColor(gpsCyan)
                    default:
                        Text(String(format: "%.1f", speed))
                            .font(.system(size: size * 0.18, weight: .bold, design: .rounded))
                            .foregroundColor(arcColor)
                    }

                    Text(unitLabel)
                        .font(.system(size: size * 0.06))
                        .foregroundColor(.secondary)
                }
            }
            .frame(width: size, height: size)
            .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
        }
    }
}

#Preview {
    VStack(spacing: 40) {
        SpeedGaugeView(speed: 0)
            .frame(height: 200)

        SpeedGaugeView(speed: 25, gpsSpeed: 22, mode: .both)
            .frame(height: 200)

        SpeedGaugeView(speed: 45, gpsSpeed: 43, mode: .gps)
            .frame(height: 200)
    }
    .padding()
}
