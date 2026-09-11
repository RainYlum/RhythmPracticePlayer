package com.rythm.app.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 教学引导步骤（FR：引导教学）：标题/正文 + 目标控件 key。
 * [targetKey] 为 null 时无高亮（纯文字步骤）；有 key 时从 bounds 取窗口坐标绘制高亮框。
 */
data class TutorialStep(
    val title: String,
    val body: String,
    val targetKey: String? = null,
)

/**
 * 通用教学 Overlay（视频库首次引导 / 播放页教学 / 更多功能教学共用）：
 * 半透明遮罩 + 目标区域高亮（主色填充 + 白色脉冲边框）+ 指向箭头 + 底部文字卡片；
 * 单击屏幕任意处推进下一步（[onAdvance]），教学期间消费全部点击不穿透到底层控件。
 *
 * 坐标空间：[bounds] 由目标控件 `Modifier.onGloballyPositioned { boundsInRoot() }` 上报，
 * 与全屏 overlay 同窗口坐标系；[fallbackTargets] 为无真实控件的目标提供兜底示意区域
 * （如沉浸退出按钮仅存在于播放态，控制态教学用屏幕左上角示意）。
 */
@Composable
fun TutorialOverlay(
    steps: List<TutorialStep>,
    stepIndex: Int,
    bounds: Map<String, Rect>,
    onAdvance: () -> Unit,
    fallbackTargets: Map<String, Rect> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val step = steps.getOrNull(stepIndex) ?: return
    val target = step.targetKey?.let { bounds[it] ?: fallbackTargets[it] }
    val pulse by rememberInfiniteTransition(label = "tutorial-pulse")
        .animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
            label = "pulse",
        )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .pointerInput(stepIndex) { detectTapGestures(onTap = { onAdvance() }) },
    ) {
        if (target != null) {
            Canvas(Modifier.fillMaxSize()) {
                // 目标区域：主色半透明填充 + 白色脉冲边框（醒目但不遮盖控件本身）
                drawRect(
                    color = Color(0x407C5CFF),
                    topLeft = target.topLeft,
                    size = target.size,
                )
                drawRect(
                    color = Color.White.copy(alpha = pulse),
                    topLeft = target.topLeft,
                    size = target.size,
                    style = Stroke(width = 5.dp.toPx()),
                )
                // 箭头：底部卡片指向目标底边的实心三角（脉冲透明度）
                val arrowW = 18.dp.toPx()
                val arrowH = 46.dp.toPx()
                val tip = Offset(target.center.x, target.bottom + 14.dp.toPx())
                val arrow = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(tip.x - arrowW, tip.y + arrowH)
                    lineTo(tip.x + arrowW, tip.y + arrowH)
                    close()
                }
                drawPath(arrow, Color.White.copy(alpha = pulse * 0.95f))
                drawLine(
                    color = Color.White.copy(alpha = pulse * 0.8f),
                    start = Offset(target.center.x, target.bottom),
                    end = tip,
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }

        // 底部文字卡片（safe 区域内，点击任意处继续）
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 44.dp),
            color = Color(0xF21C2230),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = step.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xCCFFFFFF),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "点击任意处继续（${stepIndex + 1}/${steps.size}）",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0x99FFFFFF),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}