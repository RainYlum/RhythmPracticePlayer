package com.rythm.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
 * 时间轴（宽长条版，详细设计 3.4.3）：直角长方条轨道上绘制进度 + 缓冲 + AB 区间透明遮罩
 * + A/B 端点手柄（拖动实时微调，FR-04.8）+ 帧标记刻度点。
 * 当前帧指示 = 刻度线 + 轨道下方三角形标记（替换圆头游标）：0ms 处同样绘制，
 * 配合轨道任意位置点击/拖动定位（0 秒处标记仅露半边难命中，点击轨道即可定位）。
 * 调整方式：拖动三角标记或点击/拖动轨道任意位置 → scrubTo（VM 内 40ms 节流精确预览）、
 * 松手 → commitScrub；拖动 A/B 端点 → 实时微调；按下 A/B 端点命中区优先进入 AB 微调。
 * AB 区间激活（[progressLocked]）时禁止进度拖动/点击改变当前帧，仅 A/B 端点可操作。
 *
 * 手势协程 key 仅 durationMs：A/B 值与进度值拖动中实时变化，若作为 key 会重启协程打断手势，
 * 故经 rememberUpdatedState 在回调内读最新值。
 */
@Composable
fun TimelineScrubber(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    loopStartMs: Long?,
    loopEndMs: Long?,
    progressLocked: Boolean = false,
    marks: List<FrameMark>,
    onScrub: (Long) -> Unit,
    onCommit: (Long) -> Unit,
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

    // 拖动的最终目标（commit 时用）：拖动中经 onScrub 实时推进，松手后精确定位
    var dragTargetMs by remember { mutableLongStateOf(0L) }
    var abDragTarget by remember { mutableStateOf<AbDragTarget?>(null) }
    var lastDragX by remember { mutableFloatStateOf(0f) }

    // 手势协程 key 仅 durationMs，须经 State 读最新参值（不用 by 解包，协程内 .value 实时）
    val latestLoopStart = rememberUpdatedState(loopStartMs)
    val latestLoopEnd = rememberUpdatedState(loopEndMs)
    val latestProgressLocked = rememberUpdatedState(progressLocked)
    val latestOnScrub = rememberUpdatedState(onScrub)
    val latestOnCommit = rememberUpdatedState(onCommit)
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
                // 当前手势是否处于进度拖动（false = 按下位命中 A/B 端点，进入端点微调）
                var thumbDragging = false

                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        lastDragX = offset.x
                        thumbDragging = false
                        when {
                            hitA(offset.x) -> {
                                abDragTarget = AbDragTarget.A
                                latestOnAbDragStarted.value(AbDragTarget.A)
                            }
                            hitB(offset.x) -> {
                                abDragTarget = AbDragTarget.B
                                latestOnAbDragStarted.value(AbDragTarget.B)
                            }
                            // AB 点激活：禁止进度条拖动/点击改变当前帧（仅 AB 端点可拖动）
                            else -> if (latestProgressLocked.value) {
                                latestOnAbDragStarted.value(null)
                            } else {
                                // 进度调整：命中三角标记直接拖，点击/按下轨道其它位置同样定位（恢复点击跳转），
                                // 0ms 处三角仅露半边难命中，点击轨道即可定位
                                thumbDragging = true
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
                        when {
                            abDragTarget == AbDragTarget.A -> latestOnNudgeA.value(deltaMs)
                            abDragTarget == AbDragTarget.B -> latestOnNudgeB.value(deltaMs)
                            thumbDragging -> {
                                dragTargetMs = toMs(change.position.x)
                                latestOnScrub.value(dragTargetMs)
                            }
                            // 按下位未命中：仅消费事件，不做任何调整
                        }
                    },
                    onDragEnd = {
                        if (thumbDragging) latestOnCommit.value(dragTargetMs)
                        abDragTarget = null
                        thumbDragging = false
                    },
                    onDragCancel = {
                        abDragTarget = null
                        thumbDragging = false
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
            val trackTop = y - trackH / 2f

            // 长方条轨道（直角，无圆头圆柱/圆点游标）
            drawRect(color = trackColor, topLeft = Offset(0f, trackTop), size = Size(w, trackH))
            // 缓冲
            if (bufferedMs > 0L) {
                drawRect(
                    color = bufferedColor,
                    topLeft = Offset(0f, trackTop),
                    size = Size((w * bufferedMs.toFloat() / durationMs).coerceAtMost(w), trackH),
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
            // 播放进度长方条
            val progressW = w * positionMs.toFloat() / durationMs
            if (progressW > 0f) {
                drawRect(
                    color = progressColor,
                    topLeft = Offset(0f, trackTop),
                    size = Size(progressW.coerceAtMost(w), trackH),
                )
            }
            // 当前帧指示：刻度线（轨道上方出头并贯穿轨道）+ 三角标记（轨道下方，尖端朝上对齐刻度线）。
            // 0ms 时同样绘制与可命中 → 起始位置也能拖动（替代原圆头游标：仅进度 > 0 才绘制且难命中）
            val tickX = progressW.coerceIn(0f, w)
            val tickAbove = 12.dp.toPx()
            drawRect(
                color = progressColor,
                topLeft = Offset(tickX - 1.5.dp.toPx(), trackTop - tickAbove),
                size = Size(3.dp.toPx(), trackH + tickAbove),
            )
            val triPath = Path().apply {
                val triHalfW = 9.dp.toPx()
                val triH = 9.dp.toPx()
                moveTo(tickX - triHalfW, trackTop + trackH + triH)
                lineTo(tickX + triHalfW, trackTop + trackH + triH)
                lineTo(tickX, trackTop + trackH)
                close()
            }
            drawPath(triPath, progressColor)
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