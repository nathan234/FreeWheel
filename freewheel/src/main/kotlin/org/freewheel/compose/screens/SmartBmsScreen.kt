package org.freewheel.compose.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freewheel.compose.WheelViewModel
import org.freewheel.core.domain.BmsSnapshot
import org.freewheel.core.utils.DisplayUtils

/**
 * BMS screen driven by KMP WheelState via ViewModel (used in Compose Navigation).
 */
@Composable
fun SmartBmsScreen(viewModel: WheelViewModel) {
    val bms by viewModel.bmsState.collectAsStateWithLifecycle()
    SmartBmsContent(bms1 = bms.bms1, bms2 = bms.bms2)
}

@Composable
internal fun SmartBmsContent(bms1: BmsSnapshot?, bms2: BmsSnapshot?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("BMS 1", style = MaterialTheme.typography.titleMedium)
        if (bms1 != null && bms1.voltage > 0) {
            BmsBlock(bms1)
        } else {
            Text("No BMS 1 data", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        Text("BMS 2", style = MaterialTheme.typography.titleMedium)
        if (bms2 != null && bms2.voltage > 0) {
            BmsBlock(bms2)
        } else {
            Text("No BMS 2 data", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BmsBlock(bms: BmsSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (bms.serialNumber.isNotEmpty()) {
            Text("Serial: ${bms.serialNumber}")
        }
        Text("Voltage: ${DisplayUtils.formatBmsVoltage(bms.voltage)}")
        Text("Current: ${DisplayUtils.formatBmsCurrent(bms.current)}")
        Text("Cell Diff: ${DisplayUtils.formatBmsCell(bms.cellDiff)}")
        if (bms.remCap > 0) {
            Text("Remaining: ${bms.remCap} / ${bms.factoryCap} mAh (${bms.remPerc}%)")
        }
        Text("Temp 1: ${DisplayUtils.formatBmsTemperature(bms.temp1)}")
        Text("Temp 2: ${DisplayUtils.formatBmsTemperature(bms.temp2)}")
        if (bms.temp3 != 0.0) Text("Temp 3: ${DisplayUtils.formatBmsTemperature(bms.temp3)}")
        if (bms.temp4 != 0.0) Text("Temp 4: ${DisplayUtils.formatBmsTemperature(bms.temp4)}")
        if (bms.temp5 != 0.0) Text("Temp 5: ${DisplayUtils.formatBmsTemperature(bms.temp5)}")
        if (bms.temp6 != 0.0) Text("Temp 6: ${DisplayUtils.formatBmsTemperature(bms.temp6)}")
        if (bms.health > 0) {
            Text("Health: ${bms.health}%")
        }
        Text("Max Cell: ${DisplayUtils.formatBmsCellLabeled(bms.maxCell, bms.maxCellNum)}")
        Text("Min Cell: ${DisplayUtils.formatBmsCellLabeled(bms.minCell, bms.minCellNum)}")
        Text("Avg Cell: ${DisplayUtils.formatBmsCell(bms.avgCell)}")

        if (bms.cellNum > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Cells (${bms.cellNum}):", style = MaterialTheme.typography.labelMedium)
            for (row in (0 until bms.cellNum).chunked(3)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (i in row) {
                        CellCard(index = i + 1, voltage = bms.cells[i], bms = bms, modifier = Modifier.weight(1f))
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CellCard(index: Int, voltage: Double, bms: BmsSnapshot, modifier: Modifier) {
    val bg = when (index) {
        bms.maxCellNum -> Color(0xFF4CAF50).copy(alpha = 0.12f)
        bms.minCellNum -> Color(0xFFF44336).copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = bg
    ) {
        Column(Modifier.padding(6.dp)) {
            Text("#$index", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                DisplayUtils.formatBmsCell(voltage),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
    }
}