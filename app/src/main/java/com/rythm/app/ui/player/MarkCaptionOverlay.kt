package com.rythm.app.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 帧标记字幕层（FR-06.6，详细设计 5.7）：顶部居中胶囊字幕，最多 3 条、行数 ≤2、正文小号（12sp）。
 * I3 关键：pointerInput 空实现 + 无 clickable → 不消费手势，事件落入沉浸层丢弃容器（AC-06.6）。
 * 窗口判定（±3s）由 PlayerViewModel 派生 activeCaptions 完成，本层只负责渲染与 150ms 淡入淡出。
 */
@Composable
fun MarkCaptionOverlay(
    captions: List<String>,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = captions.isNotEmpty(),
        modifier = modifier.pointerInput(Unit) {}, // 点击穿透：不消费任何手势
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            captions.take(3).forEach { text ->
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color(0x73000000), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}