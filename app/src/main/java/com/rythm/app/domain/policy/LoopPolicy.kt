package com.rythm.app.domain.policy

/**
 * 选区校验结果。
 * [Valid] 返回原始标定点；A/B 对齐关键帧后的真实边界（A'/B'）由引擎 seek 后回读，
 * ViewModel 保存回读值（架构 P1-7 修正：ClippingConfiguration 无关键帧对齐参数）。
 */
sealed class LoopValidation {
    data class Valid(val startMs: Long, val endMs: Long) : LoopValidation()

    data class Invalid(val reason: Reason) : LoopValidation()

    enum class Reason {
        /** B 早于或等于 A。 */
        B_BEFORE_A,

        /** 区间 < max(2 帧时长, 100ms)，或 B 超出视频时长（FR-04.6）。 */
        TOO_SHORT,

        /** 未设置 A 点即设置 B 点（FR-04.2）。 */
        MISSING_A,
    }
}

/**
 * 选区合法性规则（架构 5.3，纯 Kotlin 可单测）：
 * - A < B；
 * - 区间 ≥ max(2 帧时长, 100ms)（FR-04.6）；
 * - B ≤ durationMs。
 * 帧率不可得时（VFR/部分容器）frameDurationMsProvider 应返回 50ms（30fps 近似）。
 */
class LoopPolicy(
    private val minIntervalMs: Long = 100L,
    private val frameDurationMsProvider: () -> Long = { 1_000L / 30 },
) {

    fun validate(aMs: Long, bMs: Long, durationMs: Long): LoopValidation {
        if (aMs < 0L || bMs < 0L) return LoopValidation.Invalid(LoopValidation.Reason.MISSING_A)
        if (bMs <= aMs) return LoopValidation.Invalid(LoopValidation.Reason.B_BEFORE_A)
        if (bMs > durationMs) return LoopValidation.Invalid(LoopValidation.Reason.TOO_SHORT)
        val minLength = maxOf(minIntervalMs, frameDurationMsProvider() * 2)
        if (bMs - aMs < minLength) return LoopValidation.Invalid(LoopValidation.Reason.TOO_SHORT)
        return LoopValidation.Valid(aMs, bMs)
    }

    companion object {
        /** 帧率 → 单帧时长（ms），帧率不可得时 30fps 近似（FR-05.3）。 */
        fun frameDurationMs(frameRate: Float?): Long =
            if (frameRate != null && frameRate > 0f) (1_000f / frameRate).toLong() else 1_000L / 30
    }
}