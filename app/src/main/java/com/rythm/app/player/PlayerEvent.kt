package com.rythm.app.player

import com.rythm.app.model.PlayerError
import com.rythm.app.model.PlayerPhase

/**
 * 播放器事件流（详细设计 3.2.1，双通道：250ms 轮询 PositionTick + 交互事务后立即回读）。
 * 全部事件在应用主线程 emit；ViewModel 单点折叠进 PlaybackUiState。
 */
sealed class PlayerEvent {

    /** 会话阶段变化（isPlaying 随附：PLAYING 不是独立 phase，见 PlayerPhase 说明）。 */
    data class StateChanged(
        val phase: PlayerPhase,
        val isPlaying: Boolean,
    ) : PlayerEvent()

    /**
     * 位置心跳。
     * @property enginePosMs 引擎累计位置（连续循环下为跨圈累计值，仅内部使用）。
     * @property normalizedPosMs 归一化对外位置（I1：连续循环 = A' + enginePos % (B'−A')；单次 = enginePos）。
     */
    data class PositionTick(
        val enginePosMs: Long,
        val normalizedPosMs: Long,
    ) : PlayerEvent()

    /** 已缓冲至（UI 缓冲条用）。 */
    data class BufferedUpTo(val positionMs: Long) : PlayerEvent()

    /** 单次区间练习播到 B'：已自动 pause + 复位 A'（I2）。 */
    data class SelectionHit(val resetToMs: Long) : PlayerEvent()

    /** 倒计时剩余毫秒（供 UI 外推基准，最终切换以 CountdownFinished 为准）。 */
    data class CountdownProgress(val remainingMs: Int) : PlayerEvent()

    /** 倒计时结束、音频已到达起播点 S（fromMs）。 */
    data class CountdownFinished(val fromMs: Long) : PlayerEvent()

    /** 画面旋转角（度）：Activity 方向决策 + UI 布局（R-11：缺失默认 0 横屏）。 */
    data class OrientationChanged(val rotationDeg: Int) : PlayerEvent()

    /** 播放失败（decode/io/seek 卡死等）。 */
    data class Failed(val error: PlayerError) : PlayerEvent()
}