package com.rythm.app.player

import android.graphics.Bitmap
import android.net.Uri
import com.rythm.app.model.PlaybackSpeed
import com.rythm.app.model.PlayerPhase
import kotlinx.coroutines.flow.Flow

/**
 * 播放引擎封装层（架构 5.3 / 详细设计 3.2）：**全工程唯一允许 import androidx.media3 的边界**。
 * 对外契约全部使用归一化时间轴值（I1/I5）：seek/位置读回/选区/倒计时均以归一化值为准。
 */
interface VideoPlayerManager {

    /** 事件流（主线程 emit，ViewModel 折叠进状态）。 */
    val events: Flow<PlayerEvent>

    /**
     * 装载视频（异步：LOADING → READY 或 Failed）。
     * @param initialPositionMs 归一化起始位置（历史恢复点，0 为片头）。
     * @param loopStartMs/loopEndMs 选区 A'/B'（对齐后回读值，null 表示无选区）。
     * @param continuousLoop 连续循环模式（源切换为 Clipping+Looping 整链重建，P1-6）。
     */
    fun load(
        uri: Uri,
        initialPositionMs: Long,
        speed: PlaybackSpeed,
        audioOffsetMs: Int,
        loopStartMs: Long?,
        loopEndMs: Long?,
        continuousLoop: Boolean,
    )

    fun play()

    fun pause()

    /** @param exact true = 精确 seek（步进/倒计时定位）；false = 就近关键帧（拖动预览，FR-05.1）。 */
    fun seekTo(positionMs: Long, exact: Boolean)

    fun setSpeed(speed: PlaybackSpeed)

    /** 设置单次区间选区（A/B 对齐值，单位 ms）。 */
    fun setSelection(startMs: Long, endMs: Long)

    fun clearSelection()

    /** 连续循环开关：开启要求选区有效，切换重建整链并保持归一化位置；关闭重建原片源。 */
    fun enableContinuousLoop(enabled: Boolean)

    /**
     * 倒计时预听（FR-10 路径 A）：回退 [leadMs]（= countdownSec×1000，3s/5s）音频先行，位置到 S 附近发 CountdownFinished。
     */
    fun startCountdown(fromMs: Long, leadMs: Long)

    /** 取消倒计时：暂停并回 S（I8 起点语义）。 */
    fun cancelCountdown()

    /** 调整音频延迟（ms，±200 上限）：正值 0~+200（音频延后）生效于环形缓冲；负值 −1~−200（音频提前）生效于视频渲染延迟；返回值=钳制后的设定值。 */
    fun setAudioOffsetMs(offsetMs: Int): Int

    /**
     * 绑定视频输出 Surface（UI 层提供 SurfaceView；null = 解绑）。
     * UI 层不接触 media3（包边界），仅传递 android.view.Surface。
     */
    fun setVideoSurface(surface: android.view.Surface?)

    /** 抓取 positionMs 处帧画面（IO 线程，压缩 ≤maxWidthPx 宽；失败返回 null）。 */
    suspend fun captureFrameAt(positionMs: Long, maxWidthPx: Int = 320): Bitmap?

    /** 方向监听（旋转由渲染层自动处理，本回调供 Activity 方向决策 + UI 布局，R-11）。 */
    fun setVideoOrientationListener(listener: ((Int) -> Unit)?)

    /** 当前播放快照（归一化位置；历史保存/进程重启恢复用）。 */
    fun getSnapshot(): PlayerSnapshot

    fun release()
}

/** 播放快照（详细设计 3.2.2：历史持久化所需全部字段）。 */
data class PlayerSnapshot(
    val phase: PlayerPhase,
    val isPlaying: Boolean,
    val enginePositionMs: Long,
    val normalizedPositionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val speed: PlaybackSpeed,
    val loopStartMs: Long?,
    val loopEndMs: Long?,
    val continuousLoop: Boolean,
    val rotationDeg: Int,
)