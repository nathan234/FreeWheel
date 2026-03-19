import SwiftUI
import FreeWheelCore

/// Convenience accessor for KMP BmsSnapshot cells (KotlinArray<KotlinDouble> → [Double]).
extension BmsSnapshot {
    var cellValues: [Double] {
        (0..<Int(cellNum)).map { cells.get(index: Int32($0))?.doubleValue ?? 0.0 }
    }
}

struct SmartBmsView: View {
    @EnvironmentObject var wheelManager: WheelManager

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(BmsLabels.shared.BMS_1)
                    .font(.headline)
                if let bms = wheelManager.bmsState.bms1 {
                    BmsBlock(bms: bms)
                } else {
                    Text(BmsLabels.shared.NO_BMS_1)
                        .foregroundColor(.secondary)
                }

                Divider()

                Text(BmsLabels.shared.BMS_2)
                    .font(.headline)
                if let bms = wheelManager.bmsState.bms2 {
                    BmsBlock(bms: bms)
                } else {
                    Text(BmsLabels.shared.NO_BMS_2)
                        .foregroundColor(.secondary)
                }
            }
            .padding()
        }
        .navigationTitle(BmsLabels.shared.TITLE)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct BmsBlock: View {
    let bms: BmsSnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !bms.serialNumber.isEmpty {
                Text("\(BmsLabels.shared.SERIAL): \(bms.serialNumber)")
            }
            Text("\(BmsLabels.shared.VOLTAGE): \(DisplayUtils.shared.formatBmsVoltage(voltage: bms.voltage))")
            Text("\(BmsLabels.shared.CURRENT): \(DisplayUtils.shared.formatBmsCurrent(current: bms.current))")
            Text("\(BmsLabels.shared.CELL_DIFF): \(DisplayUtils.shared.formatBmsCell(voltage: bms.cellDiff))")
            if bms.remCap > 0 {
                Text("\(BmsLabels.shared.REMAINING): \(bms.remCap) / \(bms.factoryCap) mAh (\(bms.remPerc)%)")
            }
            Text("\(BmsLabels.shared.TEMP_1): \(DisplayUtils.shared.formatBmsTemperature(celsius: bms.temp1))")
            Text("\(BmsLabels.shared.TEMP_2): \(DisplayUtils.shared.formatBmsTemperature(celsius: bms.temp2))")
            if bms.temp3 != 0.0 {
                Text("\(BmsLabels.shared.TEMP_3): \(DisplayUtils.shared.formatBmsTemperature(celsius: bms.temp3))")
            }
            if bms.temp4 != 0.0 {
                Text("\(BmsLabels.shared.TEMP_4): \(DisplayUtils.shared.formatBmsTemperature(celsius: bms.temp4))")
            }
            if bms.temp5 != 0.0 {
                Text("\(BmsLabels.shared.TEMP_5): \(DisplayUtils.shared.formatBmsTemperature(celsius: bms.temp5))")
            }
            if bms.temp6 != 0.0 {
                Text("\(BmsLabels.shared.TEMP_6): \(DisplayUtils.shared.formatBmsTemperature(celsius: bms.temp6))")
            }
            if bms.health > 0 {
                Text("\(BmsLabels.shared.HEALTH): \(bms.health)%")
            }
            Text("\(BmsLabels.shared.MAX_CELL): \(DisplayUtils.shared.formatBmsCellLabeled(voltage: bms.maxCell, cellNum: bms.maxCellNum))")
            Text("\(BmsLabels.shared.MIN_CELL): \(DisplayUtils.shared.formatBmsCellLabeled(voltage: bms.minCell, cellNum: bms.minCellNum))")
            Text("\(BmsLabels.shared.AVG_CELL): \(DisplayUtils.shared.formatBmsCell(voltage: bms.avgCell))")

            if bms.cellNum > 0 {
                Divider()
                Text("\(BmsLabels.shared.CELLS) (\(bms.cellNum)):")
                    .font(.subheadline)
                    .fontWeight(.medium)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 6) {
                    ForEach(0..<Int(bms.cellNum), id: \.self) { i in
                        CellCard(index: i + 1, voltage: bms.cellValues[i], bms: bms)
                    }
                }
            }
        }
    }
}

private struct CellCard: View {
    let index: Int
    let voltage: Double
    let bms: BmsSnapshot

    private var backgroundColor: Color {
        if index == Int(bms.maxCellNum) {
            return Color.green.opacity(0.12)
        } else if index == Int(bms.minCellNum) {
            return Color.red.opacity(0.12)
        }
        return Color(UIColor.secondarySystemGroupedBackground)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("#\(index)")
                .font(.system(size: 10))
                .foregroundColor(.secondary)
            Text(DisplayUtils.shared.formatBmsCell(voltage: voltage))
                .font(.system(size: 13, design: .monospaced))
                .fontWeight(.bold)
        }
        .padding(6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(backgroundColor)
        .cornerRadius(6)
    }
}
