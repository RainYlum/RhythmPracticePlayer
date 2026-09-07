package com.rythm.app.domain.policy

/** 步进请求（FR-05.2/05.5）。frameRate 为 null 表示帧率不可得（VFR/部分容器）。 */
data class StepRequest(
    val baseMs: Long,
    val deltaMs: Long,
    val frameRate: Float? = null,
)

/** 步进决策。approximate=true 时 UI 提示"步进粒度不精确"（FR-05.3）。 */
data class StepDecision(
    val targetMs: Long,
    val approximate: Boolean,
)

/**
 * 帧步进计算（架构 P2-10 双向契约收敛）：
 * 输入 = 目标步长 + 引擎回传的实际落点（PositionInfo），输出 = 下一次 seek 目标。
 * 不做"seek 补偿再校正"（物理不收敛，P0-2）；仅在实际落点偏差超容差时标记近似。
 *
 * 近似判定：小步长（< 500ms，即 0.1s/帧档）且 |实际落点 − 请求目标| > 容差 → approximate；
 * 1s 档不标近似（关键帧间隔相对 1s 步进偏差可忽略）。
 */
class FrameStepPolicy(
    private val frameToleranceMs: Long = 50L,
) {

    fun planStep(request: StepRequest, lastActualMs: Long): StepDecision {
        val requestedTarget = request.baseMs + request.deltaMs
        // 目标基于引擎实际落点（详细设计 3.1.3：target = lastActual + delta），防止基准偏差累积
        val target = (lastActualMs + request.deltaMs).coerceAtLeast(0L)
        val approximate = kotlin.math.abs(request.deltaMs) < 500L &&
            kotlin.math.abs(lastActualMs - requestedTarget) > frameToleranceMs
        return StepDecision(targetMs = target, approximate = approximate)
    }

    companion object {
        /** 0.1s 微步档（FR-05.2 默认档）。 */
        const val STEP_100MS = 100L

        /** 1s 步进档（FR-05.2 可切换档）。 */
        const val STEP_1S = 1_000L
    }
}