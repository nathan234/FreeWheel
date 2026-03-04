import SwiftUI

struct GaugeTileView: View {
    let label: String
    let value: String
    let unit: String
    let progress: Double
    let color: Color
    let sparklineData: [Double]
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                // Label at top-left
                VStack {
                    HStack {
                        Text(label.uppercased())
                            .font(.caption2)
                            .fontWeight(.medium)
                            .foregroundColor(.secondary)
                        Spacer()
                    }
                    Spacer()
                }

                // Gauge arc
                GeometryReader { geometry in
                    let size = min(geometry.size.width, geometry.size.height)
                    let lineWidth = size * 0.06
                    let arcRadius = size * 0.38

                    ZStack {
                        // Background arc
                        Circle()
                            .trim(from: 0.5, to: 1.0)
                            .stroke(
                                Color.gray.opacity(0.15),
                                style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                            )
                            .frame(width: arcRadius * 2, height: arcRadius * 2)

                        // Progress arc
                        Circle()
                            .trim(from: 0.5, to: 0.5 + (0.5 * min(max(progress, 0), 1)))
                            .stroke(
                                color,
                                style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                            )
                            .frame(width: arcRadius * 2, height: arcRadius * 2)
                            .animation(.spring(response: 0.3), value: progress)
                    }
                    .position(x: geometry.size.width / 2, y: geometry.size.height * 0.48)
                }

                // Center value + unit
                VStack(spacing: 1) {
                    Text(value)
                        .font(.system(size: 20, weight: .bold, design: .rounded))
                        .foregroundColor(color)
                    Text(unit)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }

                // Sparkline in bottom-right
                if sparklineData.count >= 2 {
                    VStack {
                        Spacer()
                        HStack {
                            Spacer()
                            SparklineShape(data: sparklineData)
                                .stroke(color.opacity(0.4), lineWidth: 1.5)
                                .frame(width: 50, height: 20)
                        }
                    }
                }
            }
            .padding(10)
            .aspectRatio(1, contentMode: .fit)
            .background(Color(UIColor.secondarySystemGroupedBackground))
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
    }
}

private struct SparklineShape: Shape {
    let data: [Double]

    func path(in rect: CGRect) -> Path {
        guard data.count >= 2 else { return Path() }
        let minVal = data.min() ?? 0
        let maxVal = data.max() ?? 1
        let range = max(maxVal - minVal, 0.01)

        var path = Path()
        for (index, value) in data.enumerated() {
            let x = rect.minX + CGFloat(index) / CGFloat(data.count - 1) * rect.width
            let y = rect.maxY - CGFloat((value - minVal) / range) * rect.height
            if index == 0 {
                path.move(to: CGPoint(x: x, y: y))
            } else {
                path.addLine(to: CGPoint(x: x, y: y))
            }
        }
        return path
    }
}

#Preview {
    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
        GaugeTileView(
            label: "Speed",
            value: "25.3",
            unit: "km/h",
            progress: 0.5,
            color: .green,
            sparklineData: [10, 15, 20, 25, 22, 25],
            action: {}
        )
        GaugeTileView(
            label: "Battery",
            value: "75",
            unit: "%",
            progress: 0.75,
            color: .green,
            sparklineData: [80, 78, 76, 75, 75, 75],
            action: {}
        )
    }
    .padding()
}
