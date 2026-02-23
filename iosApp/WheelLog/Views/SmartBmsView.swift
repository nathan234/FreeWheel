import SwiftUI
import WheelLogCore

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
                if let bms = wheelManager.wheelState.bms1 {
                    BmsBlock(bms: bms)
                } else {
                    Text(BmsLabels.shared.NO_BMS_1)
                        .foregroundColor(.secondary)
                }

                Divider()

                Text(BmsLabels.shared.BMS_2)
                    .font(.headline)
                if let bms = wheelManager.wheelState.bms2 {
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
            if bms.remCap > 0 {
                Text("\(BmsLabels.shared.REMAINING): \(bms.remCap) / \(bms.factoryCap) mAh (\(bms.remPerc)%)")
            }
            Text("\(BmsLabels.shared.TEMP_1): \(DisplayUtils.shared.formatBmsTemperature(celsius: bms.temp1))")
            Text("\(BmsLabels.shared.TEMP_2): \(DisplayUtils.shared.formatBmsTemperature(celsius: bms.temp2))")
            if bms.health > 0 {
                Text("\(BmsLabels.shared.HEALTH): \(bms.health)%")
            }
            Text("\(BmsLabels.shared.MAX_CELL): \(DisplayUtils.shared.formatBmsCellLabeled(voltage: bms.maxCell, cellNum: bms.maxCellNum))")
            Text("\(BmsLabels.shared.MIN_CELL): \(DisplayUtils.shared.formatBmsCellLabeled(voltage: bms.minCell, cellNum: bms.minCellNum))")
            Text("\(BmsLabels.shared.CELL_DIFF): \(DisplayUtils.shared.formatBmsCell(voltage: bms.cellDiff))")
            Text("\(BmsLabels.shared.AVG_CELL): \(DisplayUtils.shared.formatBmsCell(voltage: bms.avgCell))")

            if bms.cellNum > 0 {
                Divider()
                Text("\(BmsLabels.shared.CELLS) (\(bms.cellNum)):")
                    .font(.subheadline)
                    .fontWeight(.medium)
                ForEach(0..<Int(bms.cellNum), id: \.self) { i in
                    Text("  \(DisplayUtils.shared.formatBmsCellIndexed(index: Int32(i + 1), voltage: bms.cellValues[i]))")
                        .font(.system(.body, design: .monospaced))
                }
            }
        }
    }
}
