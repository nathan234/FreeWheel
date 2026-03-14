import SwiftUI
import FreeWheelCore

/// Generic arc gauge for non-speed hero metrics (PWM, Battery, Power, etc.).
/// Simpler than SpeedGaugeView — no GPS mode, no speed-specific tick marks.
struct HeroGaugeView: View {
    let value: Double
    let maxValue: Double
    let unitLabel: String
    let label: String
    let metric: DashboardMetric

    private var effectiveMax: Double {
        maxValue > 0 ? maxValue : max(abs(value), 1.0)
    }

    private var progress: Double {
        (abs(value) / effectiveMax).clamped(to: 0...1)
    }

    private var arcColor: Color {
        let zone = metric.colorZone(progress: progress)
        switch zone {
        case .green: return .green
        case .orange: return .orange
        case .red: return .red
        default: return .gray
        }
    }

    private var formattedValue: String {
        String(format: "%.\(metric.decimals)f", value)
    }

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height * 2)
            let strokeWidth: CGFloat = 16

            ZStack {
                // Track arc
                ArcShape(progress: 1.0)
                    .stroke(Color(UIColor.systemGray5), style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round))
                    .frame(width: size - strokeWidth, height: (size - strokeWidth) / 2)

                // Progress arc
                ArcShape(progress: progress)
                    .stroke(arcColor, style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round))
                    .frame(width: size - strokeWidth, height: (size - strokeWidth) / 2)

                // Value text
                VStack(spacing: 2) {
                    Text(formattedValue)
                        .font(.system(size: 52, weight: .bold, design: .rounded))
                    Text(unitLabel)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    Text(label)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .offset(y: 20)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

private struct ArcShape: Shape {
    let progress: Double

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let center = CGPoint(x: rect.midX, y: rect.maxY)
        let radius = min(rect.width / 2, rect.height)
        path.addArc(
            center: center,
            radius: radius,
            startAngle: .degrees(180),
            endAngle: .degrees(180 + 180 * progress),
            clockwise: false
        )
        return path
    }
}

private extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        return min(max(self, limits.lowerBound), limits.upperBound)
    }
}
