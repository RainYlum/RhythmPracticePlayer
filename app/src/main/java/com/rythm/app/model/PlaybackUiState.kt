package com.rythm.app.model

/**
 * UI 唯一状态源（架构 5.2）。
 * @property videoUri uri 字符串（model 层纯 Kotlin，不依赖 Android Uri 类型）。
 * @property displayPositionMs 最近引擎确认位置（AC-02.3：UI 按 tickBase* 外推展示，见 [extrapolatedDisplayPositionMs]）。
 * @property countdownRemainingMs >0 表示处于倒计时预听阶段（FR-10）。
 * @property tickBasePositionMs/tickBaseElapsedMs/tickBaseSpeed 每帧外推基准（架构 6.3.1）：
 *   外推 = tickBasePositionMs + tickBaseSpeed×(now − tickBaseElapsedMs)，由 ViewModel 在每次
 *   PositionTick/seek/变速/暂停/倒计时结束时重置。
 */
data class PlaybackUiState(
    val videoUri: String? = null,
    val isPlaying: Boolean = false,
    val isImmersive: Boolean = false,
    val countdownRemainingMs: Int = 0,
    val currentPositionMs: Long = 0L,
    val displayPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: PlaybackSpeed = PlaybackSpeed.DEFAULT,
    val loopEnabled: Boolean = false,
    val continuousLoop: Boolean = false,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val videoRotationDeg: Int = 0,
    val audioOffsetMs: Int = 0,
    val countdownSec: Int = 3,
    val showMillis: Boolean = false,
    val error: PlayerError? = null,
    val phase: PlayerPhase = PlayerPhase.IDLE,
    val tickBasePositionMs: Long = 0L,
    val tickBaseElapsedMs: Long = 0L,
    val tickBaseSpeed: PlaybackSpeed = PlaybackSpeed.DEFAULT,
) {
    /** CONTROL 控制态判定（详细设计 3.4.2 + FR-02.5）：暂停控制窗口；沉浸播放（READY+isPlaying）不渲染控制 UI；ENDED 播完展示重播控制。 */
    val controlVisible: Boolean
        get() = phase == PlayerPhase.COUNTDOWN || (phase == PlayerPhase.READY && !isPlaying) || phase == PlayerPhase.ENDED

    /** IMMERSIVE 沉浸态判定：播放中且非倒计时。 */
    val immersive: Boolean
        get() = isPlaying && !isCountdown

    val isCountdown: Boolean
        get() = phase == PlayerPhase.COUNTDOWN

    /** 播放中每帧外推位置（AC-02.3）：其余阶段返回引擎确认值。 */
    fun extrapolatedDisplayPositionMs(nowMs: Long): Long =
        if (immersive) tickBasePositionMs + ((nowMs - tickBaseElapsedMs).coerceAtLeast(0L) * tickBaseSpeed.value).toLong()
        else displayPositionMs
}