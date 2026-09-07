package com.rythm.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 音频延迟面板（FR-08，详细设计 5.3）：暂停态调节，±5ms 步进、区间 [−100, +100]、一键复位。
 * 正值 = 音频延后（常见校准方向）；负值 = 音频提前（等价视频延后）。
 * 改动经 VM.setAudioOffsetMs → manager.setAudioOffsetMs 重建音频链即时生效。
 */
@Composable
fun AudioDelayPanel(
    offsetMs: Int,
    onOffset: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("音频延迟", color = Color.White, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onOffset(offsetMs - 5) }, enabled = offsetMs > -100) {
                Text("−5", color = Color.White)
            }
            Text(
                text = when {
                    offsetMs == 0 -> "0ms（同步）"
                    offsetMs > 0 -> "+${offsetMs}ms（音频延后）"
                    else -> "${offsetMs}ms（音频提前）"
                },
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(120.dp), // 固定宽度防文本跳动
            )
            TextButton(onClick = { onOffset(offsetMs + 5) }, enabled = offsetMs < 100) {
                Text("+5", color = Color.White)
            }
            TextButton(onClick = { onOffset(0) }, enabled = offsetMs != 0) {
                Text("复位", color = Color.White)
            }
        }
    }
}