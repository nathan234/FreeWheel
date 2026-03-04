package org.freewheel.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.freewheel.core.domain.AlarmType
import org.freewheel.core.domain.CommonLabels

@Composable
fun AlarmBanner(
    activeAlarms: Set<AlarmType>,
    modifier: Modifier = Modifier
) {
    if (activeAlarms.isEmpty()) return

    var isPulsing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isPulsing = true }

    val backgroundColor by animateColorAsState(
        targetValue = if (isPulsing) Color(0xFFF44336) else Color(0xFFFF9800),
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alarm_pulse"
    )

    val alarmText = activeAlarms.sortedBy { it.value }.joinToString(", ") { it.displayName }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(vertical = 10.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Alarm",
            tint = Color.White
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${CommonLabels.ALARM_PREFIX}$alarmText",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}
