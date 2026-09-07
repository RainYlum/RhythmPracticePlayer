package com.rythm.app.model

/**
 * 倍速六档固定预设（FR-03.1，v1.4 收敛：无滑块连续微调）。
 * 任何 Float 倍速必须经 [from] 归档后再下放引擎（详细设计 I4）。
 */
enum class PlaybackSpeed(val value: Float) {

    S0_5(0.5f),
    S0_6(0.6f),
    S0_7(0.7f),
    S0_8(0.8f),
    S0_9(0.9f),
    S1_0(1.0f),
    ;

    /** 倍速档位 UI 标签（倍速 chip 组与历史显示用）。 */
    val label: String get() = "${value}x"

    companion object {
        /** 取最近档位（引擎回读值归档用）。 */
        fun from(value: Float): PlaybackSpeed = entries.minBy { kotlin.math.abs(it.value - value) }

        val DEFAULT: PlaybackSpeed = S1_0
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 1.0f
    }
}