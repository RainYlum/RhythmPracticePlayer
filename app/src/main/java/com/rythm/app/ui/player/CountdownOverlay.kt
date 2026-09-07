package com.rythm.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 倒计时遮罩（FR-10，详细设计 3.4.2 时序 ⑥⑦⑧）：半透明遮罩 + 3-2-1 大字 + 取消按钮。
 * 增强位：可铺倒计时起点 S 帧截图（FrameGrabber，时序 ④）实现"静止画面"；
 * 当前以半透明黑色遮罩压暗画面保证感知。取消 → VM.cancelCountdown（pause + seekTo(S)）。
 */
@Composable
fun CountdownOverlay(
    remainingMs: Int,
    onCancel: () -> Unit,
) {
    val display = ((remainingMs + 999) / 1000).coerceIn(0, 99)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x59000000)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$display",
            color = Color.White,
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
        )
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
        ) {
            Text("取消", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}