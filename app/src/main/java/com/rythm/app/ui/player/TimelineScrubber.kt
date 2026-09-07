package com.rythm.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.rythm.app.model.FrameMark
import kotlin.math.abs

/** AB 端点拖动目标（微调显隐契约：拖动 A → 显示 A 微调；拖动 B → 显示 B 微调；进度拖动/点击 → null）。 */
enum class AbDragTarget { A, B }

/**
 * 时间轴（宽长条版，详细设计 3.4.3）：宽条轨道上绘制进度 + 缓冲 + AB 区间透明遮罩
 * + A/B 端点手柄（拖动实时微调，FR-04.8）+ 帧标记刻度点；进度拖动 → scrubTo（VM 内 80ms 节流）、
 * 松手 → commitScrub；点击区间色带 → 跳 A；点击 A/B 手柄本身不触发任何跳转。
 *
 * 手势协程 key 仅 durationMs：A/B 值拖动中实时变化，若作为 key 会重启协程打断手势，
 * 故经 rememberUpdatedState 在回调内读最新值。
 */
@Composable
fun TimelineScrubber(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    loopStartMs: Long?,
    loopEndMs: Long?,
    marks: List<FrameMark>,
    onScrub: (Long) -> Unit,
    onCommit: (Long) -> Unit,
    onTapLoopBand: () -> Unit,
    onNudgeA: (Long) -> Unit,
    onNudgeB: (Long) -> Unit,
    onAbDragStarted: (AbDragTarget?) -> Unit,
) {
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val bufferedColor = Color(0x66FFFFFF)
    val progressColor = MaterialTheme.colorScheme.primary
    val loopColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.38f)
    val abEdgeColor = MaterialTheme.colorScheme.tertiary
    val markColor = MaterialTheme.colorScheme.tertiary

    var dragging by remember { mutableStateOf(false) }
    var dragTargetMs by remember { mutableLongStateOf(0L) }
    var abDragTarget by remember { mutableStateOf<AbDragTarget?>(null) }
    var lastDragX by remember { mutableFloatStateOf(0f) }

    // 手势协程 key 仅 durationMs，须经 State 读最新参值（不用 by 解包，协程内 .value 实时）
    val latestLoopStart = rememberUpdatedState(loopStartMs)
    val latestLoopEnd = rememberUpdatedState(loopEndMs)
    val latestOnScrub = rememberUpdatedState(onScrub)
    val latestOnCommit = rememberUpdatedState(onCommit)
    val latestOnTapLoopBand = rememberUpdatedState(onTapLoopBand)
    val latestOnNudgeA = rememberUpdatedState(onNudgeA)
    val latestOnNudgeB = rememberUpdatedState(onNudgeB)
    val latestOnAbDragStarted = rememberUpdatedState(onAbDragStarted)

    val hitRadiusPx = with(LocalDensity.current) { 18.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(durationMs) {
                val toMs: (Float) -> Long = { x ->
                    if (size.width <= 0f) 0L
                    else (x / size.width * durationMs).toLong().coerceIn(0L, durationMs)
                }
                val xOfMs: (Long) -> Float = { ms ->
                    if (durationMs <= 0L) 0f else ms.toFloat() / durationMs * size.width
                }
                val hitA: (Float) -> Boolean = { x ->
                    val a = latestLoopStart.value
                    a != null && abs(x - xOfMs(a)) <= hitRadiusPx
                }
                val hitB: (Float) -> Boolean = { x ->
                    val b = latestLoopEnd.value
                    b != null && abs(x - xOfMs(b)) <= hitRadiusPx
                }

                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        lastDragX = offset.x
                        when {
                            hitA(offset.x) -> {
                                abDragTarget = AbDragTarget.A
                                latestOnAbDragStarted.value(AbDragTarget.A)
                            }
                            hitB(offset.x) -> {
                                abDragTarget = AbDragTarget.B
                                latestOnAbDragStarted.value(AbDragTarget.B)
                            }
                            else -> {
                                dragging = true
                                dragTargetMs = toMs(offset.x)
                                latestOnScrub.value(dragTargetMs)
                                latestOnAbDragStarted.value(null)
                            }
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val deltaMs = ((change.position.x - lastDragX) / size.width * durationMs).toLong()
                        lastDragX = change.position.x
                        when (abDragTarget) {
                            AbDragTarget.A -> latestOnNudgeA.value(deltaMs)
                            AbDragTarget.B -> latestOnNudgeB.value(deltaMs)
                            null -> {
                                dragTargetMs = toMs(change.position.x)
                                latestOnScrub.value(dragTargetMs)
                            }
                        }
                    },
                    onDragEnd = {
                        if (abDragTarget == null) latestOnCommit.value(dragTargetMs)
                        dragging = false
                        abDragTarget = null
                    },
                    onDragCancel = {
                        dragging = false
                        abDragTarget = null
                    },
                )
            }
            .pointerInput(durationMs) {
                val toMs: (Float) -> Long = { x ->
                    if (size.width <= 0f) 0L
                    else (x / size.width * durationMs).toLong().coerceIn(0L, durationMs)
                }
                val xOfMs: (Long) -> Float = { ms ->
                    if (durationMs <= 0L) 0f else ms.toFloat() / durationMs * size.width
                }
                detectTapGestures(
                    onTap = { offset ->
                        val a = latestLoopStart.value
                        val b = latestLoopEnd.value
                        val onHandle = (a != null && abs(offset.x - xOfMs(a)) <= hitRadiusPx) ||
                            (b != null && abs(offset.x - xOfMs(b)) <= hitRadiusPx)
                        if (!onHandle) {
                            latestOnAbDragStarted.value(null)
                            val inBand = a != null && b != null && toMs(offset.x) in a..b
                            if (inBand) latestOnTapLoopBand.value() else latestOnCommit.value(toMs(offset.x))
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (durationMs <= 0L) return@Canvas
            val w = size.width
            val h = size.height
            val y = h / 2f
            val trackH = 14.dp.toPx()
            val radius = trackH / 2f

            // 宽条轨道
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, y - radius),
                size = Size(w, trackH),
                cornerRadius = CornerRadius(radius),
            )
            // 缓冲
            if (bufferedMs > 0L) {
                drawRoundRect(
                    color = bufferedColor,
                    topLeft = Offset(0f, y - radius),
                    size = Size((w * bufferedMs.toFloat() / durationMs).coerceAtMost(w), trackH),
                    cornerRadius = CornerRadius(radius),
                )
            }
            // AB 区间透明遮罩 + 端点竖线（FR-04.5：按对齐后 A'/B' 绘制）
            val start = latestLoopStart.value
            val end = latestLoopEnd.value
            if (start != null && end != null && end > start) {
                val x0 = w * start.toFloat() / durationMs
                val x1 = w * end.toFloat() / durationMs
                drawRect(
                    color = loopColor,
                    topLeft = Offset(x0, 0f),
                    size = Size((x1 - x0).coerceAtLeast(1f), h),
                )
                drawRect(abEdgeColor, Offset((x0 - 1.dp.toPx()).coerceAtLeast(0f), 0f), Size(2.dp.toPx(), h))
                drawRect(abEdgeColor, Offset((x1 - 1.dp.toPx()).coerceIn(0f, w), 0f), Size(2.dp.toPx(), h))
            }
            // 帧标记刻度点（FR-06，TimelineScrubber 预留扩展位）
            marks.forEach { mark ->
                val mx = w * mark.timeMs.toFloat() / durationMs
                drawCircle(
                    color = markColor,
                    radius = 3.dp.toPx(),
                    center = Offset(mx.coerceIn(0f, w), y),
                )
            }
            // 播放进度宽条 + 游标
            val progressW = w * positionMs.toFloat() / durationMs
            if (progressW > 0f) {
                drawRoundRect(
                    color = progressColor,
                    topLeft = Offset(0f, y - radius),
                    size = Size(progressW.coerceAtMost(w), trackH),
                    cornerRadius = CornerRadius(radius),
                )
                drawCircle(
                    color = progressColor,
                    radius = 8.dp.toPx(),
                    center = Offset(progressW.coerceIn(0f, w), y),
                )
            }
            if (start != null) {
                drawAbHandle(
                    center = Offset((w * start.toFloat() / durationMs).coerceIn(0f, w), y),
                    isDragging = abDragTarget == AbDragTarget.A,
                    color = abEdgeColor,
                )
            }
            if (end != null) {
                drawAbHandle(
                    center = Offset((w * end.toFloat() / durationMs).coerceIn(0f, w), y),
                    isDragging = abDragTarget == AbDragTarget.B,
                    color = abEdgeColor,
                )
            }
            // 拖动预览光标
            if (dragging) {
                val dx = w * dragTargetMs.toFloat() / durationMs
                drawCircle(
                    color = Color.White,
                    radius = 8.dp.toPx(),
                    center = Offset(dx.coerceIn(0f, w), y),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

/** AB 端点圆形手柄：选中/拖动中放大。颜色由调用方传入（绘制 scope 内不能取 @Composable 主题色）。 */
private fun DrawScope.drawAbHandle(center: Offset, isDragging: Boolean, color: Color) {
    val r = if (isDragging) 12.dp.toPx() else 9.dp.toPx()
    drawCircle(color = color, radius = r, center = center)
    drawCircle(
        color = Color.White,
        radius = r,
        center = center,
        style = Stroke(width = 2.dp.toPx()),
    )
}