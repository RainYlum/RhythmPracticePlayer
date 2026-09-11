package com.rythm.app.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.rythm.app.model.PlaybackSpeed
import com.rythm.app.model.PlayerError
import com.rythm.app.model.PlayerErrorCode
import com.rythm.app.model.PlayerPhase
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * ExoPlayer 引擎封装实现（架构 5.3 / 详细设计 3.2）：
 * - ExoPlayer 装配：硬解失败降软解（setEnableDecoderFallback）、关闭内建音频焦点（handleAudioFocus=false，
 *   焦点由 [AudioFocusHelper] 接管）、视频等比 FIT；
 * - PlayerObserver 双通道：250ms 轮询 PositionTick + 交互事务（seek/步进/复位/倒计时结束）后立即回读 emit；
 * - SelectionMonitor：单次区间播到 B' 自动 pause + 复位 A'（I2，不重建源）；
 * - LoopMediaSourceBuilder：连续循环整链重建（P1-6），源切换原语 = 暂停→建链→prepare→等 READY→seek 回归一化→恢复状态；
 * - CountdownController：FR-10 路径 A（音频先行 + 静止帧遮罩由 UI 层负责）；
 * - AudioDelayProcessor：FR-08 正值延迟 + Sonic 变速共存；负值（音频提前）由 [VideoDelayRenderer]
 *   将视频渲染时钟整体平移等量延迟，正负均真实生效（±200ms）。
 *
 * 线程约束：除 [captureFrameAt] 外全部方法须在应用主线程调用（ExoPlayer 约定）。
 */
class VideoPlayerManagerImpl(
    private val context: Context,
) : VideoPlayerManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _events = MutableSharedFlow<PlayerEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<PlayerEvent> = _events.asSharedFlow()

    // ==== 内部组件 ====
    private val delayProcessor = AudioDelayProcessor()
    /** 视频渲染延迟（负音频延迟 = 视频延后等量，渲染线程读取，主线程写入 → 原子量）。 */
    private val videoFrameDelayUs = AtomicLong(0L)
    private val delayController = AudioDelayController(delayProcessor) { us -> videoFrameDelayUs.set(us) }
    private val sourceBuilder: LoopMediaSourceBuilder by lazy {
        LoopMediaSourceBuilder(DefaultMediaSourceFactory(context))
    }
    private val frameGrabber: FrameGrabber by lazy { FrameGrabber(context) }
    private val focusHelper: AudioFocusHelper
        get() = focusHelperRef ?: AudioFocusHelper(context) { granted, duckOnly ->
            if (!granted && !duckOnly) {
                // 焦点丢失（非 duck）→ 暂停；恢复焦点不自动播放（架构 6.4）
                exoPlayer.pause()
                setPhase(PlayerPhase.READY, isPlaying = false)
                emitTick()
            }
        }.also { focusHelperRef = it }

    private var focusHelperRef: AudioFocusHelper? = null

    private val selectionMonitor = SelectionMonitor(onHit = ::onSelectionHit)

    private val countdown = CountdownController(
        seekTo = { pos, exact -> seekTo(pos, exact) },
        doPlay = { exoPlayer.play() },
        doPause = { exoPlayer.pause() },
        currentPosition = { displayPosMs },
        onFinished = { fromMs ->
            setPhase(PlayerPhase.READY, isPlaying = exoPlayer.isPlaying)
            _events.tryEmit(PlayerEvent.CountdownFinished(fromMs))
            emitTick()
        },
        onFallback = { message ->
            _events.tryEmit(PlayerEvent.Failed(PlayerError(PlayerErrorCode.SEEK_STUCK, message, recoverable = true)))
        },
    )

    private val orientationReader: OrientationReader by lazy { OrientationReader(context) }

    private val exoPlayer: ExoPlayer
        get() = exoPlayerRef ?: createPlayer().also { exoPlayerRef = it }

    private var exoPlayerRef: ExoPlayer? = null

    private fun createPlayer(): ExoPlayer {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_MOVIE)
            .build()
        return ExoPlayer.Builder(context, AudioDelayRenderersFactory(context, delayProcessor) { videoFrameDelayUs.get() })
            .setAudioAttributes(attributes, /* handleAudioFocus= */ false)
            .setSeekParameters(SeekParameters.DEFAULT)
            .build()
            .apply {
                setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                addListener(playerListener)
            }
    }

    // ==== 会话状态 ====
    private var loadedUri: Uri? = null
    private var isPrepared = false
    private var firstReady = true
    private var phase = PlayerPhase.IDLE
    /** 上一报的 isPlaying（setPhase 去重守卫：phase 未变但 isPlaying 翻转时也必须上报 StateChanged）。 */
    private var reportedIsPlaying = false
    private var currentSpeed = PlaybackSpeed.DEFAULT
    private var selectionStartMs: Long? = null
    private var selectionEndMs: Long? = null
    private var continuousLoop = false
    private var durationMs = 0L
    private var rotationDeg = 0
    private var displayPosMs = 0L
    private var orientationListener: ((Int) -> Unit)? = null
    private var tickerJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> setPhase(if (firstReady) PlayerPhase.LOADING else PlayerPhase.BUFFERING)
                Player.STATE_READY -> {
                    firstReady = false
                    isPrepared = true
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    setPhase(PlayerPhase.READY)
                    emitTick()
                }
                Player.STATE_ENDED -> setPhase(PlayerPhase.ENDED, isPlaying = false)
                Player.STATE_IDLE -> {
                    // 错误已由 onPlayerError 上报；IDLE 且未错误 = release 前夜，忽略
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            setPhase(phase, isPlaying = isPlaying)
            if (!isPlaying) emitTick()
        }

        override fun onPositionDiscontinuity(
            oldPositionInfo: Player.PositionInfo,
            newPositionInfo: Player.PositionInfo,
            reason: Int,
        ) {
            // 交互事务通道：seek/跨圈跳转后立即回读
            emitTick()
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }
    }

    // ==== 对外接口 ====

    override fun load(
        uri: Uri,
        initialPositionMs: Long,
        speed: PlaybackSpeed,
        audioOffsetMs: Int,
        loopStartMs: Long?,
        loopEndMs: Long?,
        continuousLoop: Boolean,
    ) {
        loadedUri = uri
        currentSpeed = speed
        selectionStartMs = loopStartMs
        selectionEndMs = loopEndMs
        this.continuousLoop = continuousLoop
        delayController.apply(audioOffsetMs)
        durationMs = 0L
        rotationDeg = 0
        displayPosMs = initialPositionMs.coerceAtLeast(0L)
        firstReady = true
        isPrepared = false
        selectionMonitor.reset()
        focusHelper.request()
        scope.launch {
            val p = exoPlayer
            p.pause()
            p.setMediaSource(sourceBuilder.build(uri, selectionStartMs, selectionEndMs, this@VideoPlayerManagerImpl.continuousLoop))
            p.prepare()
            setPhase(PlayerPhase.LOADING, isPlaying = false)
            val rotationDeferred = async(Dispatchers.IO) { orientationReader.readRotation(uri) }
            if (!awaitReady(LOAD_TIMEOUT_MS)) {
                _events.tryEmit(
                    PlayerEvent.Failed(PlayerError(PlayerErrorCode.IO_ERROR, "视频加载超时或失败", recoverable = true)),
                )
                return@launch
            }
            if (initialPositionMs > 0L) p.seekTo(initialPositionMs)
            p.setPlaybackSpeed(currentSpeed.value)
            // 方向上报（渲染层已自动旋转画面；本值仅供 Activity 方向 + UI 布局，R-11）
            rotationDeg = rotationDeferred.await()
            if (rotationDeg != 0) {
                _events.tryEmit(PlayerEvent.OrientationChanged(rotationDeg))
                orientationListener?.invoke(rotationDeg)
            }
            startTicker()
        }
    }

    override fun play() {
        if (!isPrepared || countdown.isActive) return
        exoPlayer.play()
    }

    override fun pause() {
        if (!isPrepared) return
        exoPlayer.pause()
        setPhase(PlayerPhase.READY, isPlaying = false)
        emitTick()
    }

    override fun seekTo(positionMs: Long, exact: Boolean) {
        if (!isPrepared) return
        val target = clampSeek(positionMs)
        if (exact) {
            exoPlayer.setSeekParameters(SeekParameters.EXACT)
        } else {
            exoPlayer.setSeekParameters(SeekParameters.DEFAULT)
        }
        exoPlayer.seekTo(target)
        displayPosMs = target
        // 交互事务立即回读（双通道，架构 6.3）
        emitTick()
    }

    override fun setSpeed(speed: PlaybackSpeed) {
        currentSpeed = speed
        if (isPrepared) exoPlayer.setPlaybackSpeed(speed.value)
    }

    override fun setSelection(startMs: Long, endMs: Long) {
        selectionStartMs = startMs
        selectionEndMs = endMs
        selectionMonitor.reset()
        if (continuousLoop) switchSource(restoreDisplayPosition()) else emitTick()
    }

    override fun clearSelection() {
        selectionStartMs = null
        selectionEndMs = null
        selectionMonitor.reset()
        if (continuousLoop) switchSource(restoreDisplayPosition()) else emitTick()
    }

    override fun enableContinuousLoop(enabled: Boolean) {
        if (enabled == continuousLoop) return
        if (enabled && (selectionStartMs == null || selectionEndMs == null)) return // 无选区不可开循环（ViewModel 保证）
        continuousLoop = enabled
        selectionMonitor.reset()
        switchSource(restoreDisplayPosition())
    }

    override fun startCountdown(fromMs: Long, leadMs: Long) {
        if (!isPrepared) return
        countdown.start(fromMs, leadMs)
        setPhase(PlayerPhase.COUNTDOWN, isPlaying = exoPlayer.isPlaying)
    }

    override fun cancelCountdown() {
        if (!countdown.isActive) return
        countdown.cancel()
        setPhase(PlayerPhase.READY, isPlaying = false)
        emitTick()
    }

    override fun setAudioOffsetMs(offsetMs: Int): Int = delayController.apply(offsetMs)

    override fun setVideoSurface(surface: android.view.Surface?) {
        exoPlayer.setVideoSurface(surface)
    }

    override suspend fun captureFrameAt(positionMs: Long, maxWidthPx: Int): Bitmap? {
        val uri = loadedUri ?: return null
        return frameGrabber.grabFrame(uri, positionMs, maxWidthPx)
    }

    override fun setVideoOrientationListener(listener: ((Int) -> Unit)?) {
        orientationListener = listener
    }

    override fun getSnapshot(): PlayerSnapshot {
        val p = exoPlayer
        val enginePos = if (isPrepared) p.currentPosition.coerceAtLeast(0L) else displayPosMs
        return PlayerSnapshot(
            phase = phase,
            isPlaying = isPrepared && p.isPlaying,
            enginePositionMs = enginePos,
            normalizedPositionMs = normalize(enginePos),
            durationMs = durationMs,
            bufferedPositionMs = if (isPrepared) p.bufferedPosition.coerceAtLeast(0L) else 0L,
            speed = currentSpeed,
            loopStartMs = selectionStartMs,
            loopEndMs = selectionEndMs,
            continuousLoop = continuousLoop,
            rotationDeg = rotationDeg,
        )
    }

    override fun release() {
        tickerJob?.cancel()
        exoPlayerRef?.let { p ->
            p.removeListener(playerListener)
            p.release()
        }
        focusHelperRef?.abandon()
        scope.cancel()
    }

    // ==== 内部实现 ====

    /** 源切换原语（架构 6.5 / P1-6）：暂停 → 整链重建 → prepare → 等 READY → seek 回归一化位置 → 恢复状态。 */
    private fun switchSource(restorePositionMs: Long) {
        val uri = loadedUri ?: return
        scope.launch {
            val p = exoPlayer
            val wasPlaying = p.isPlaying
            p.pause()
            p.setMediaSource(sourceBuilder.build(uri, selectionStartMs, selectionEndMs, continuousLoop))
            p.prepare()
            if (!awaitReady(SOURCE_SWITCH_TIMEOUT_MS)) {
                _events.tryEmit(
                    PlayerEvent.Failed(PlayerError(PlayerErrorCode.IO_ERROR, "源切换失败", recoverable = true)),
                )
                return@launch
            }
            p.seekTo(restorePositionMs.coerceAtLeast(0L))
            p.setPlaybackSpeed(currentSpeed.value)
            isPrepared = true
            displayPosMs = restorePositionMs
            setPhase(PlayerPhase.READY, isPlaying = wasPlaying)
            if (wasPlaying) p.play()
            emitTick()
        }
    }

    private suspend fun awaitReady(timeoutMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        exoPlayer.removeListener(this)
                        if (cont.isActive) cont.resume(true)
                    }
                    Player.STATE_IDLE -> {
                        exoPlayer.removeListener(this)
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        if (exoPlayer.playbackState == Player.STATE_READY) {
            exoPlayer.removeListener(listener)
            cont.resume(true)
        } else {
            cont.invokeOnCancellation { exoPlayer.removeListener(listener) }
            scope.launch {
                delay(timeoutMs)
                if (cont.isActive) {
                    exoPlayer.removeListener(listener)
                    cont.resume(false)
                }
            }
        }
    }

    /** 250ms 位置轮询（双通道之一定时通道）。 */
    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(POSITION_TICK_MS)
                emitTick()
                countdown.onPositionTick(displayPosMs)
                countdown.remainingMs()?.let { _events.tryEmit(PlayerEvent.CountdownProgress(it)) }
                // 仅播放中监听选区越界（I2「播到 B' 结束练习」）：暂停态 seek/端点拖动/A/B 微调预览
                // 落点 ≥ B' 时不得触发复位（否则拖动 B 端点会被拉回 A），播放起点恒为 A' 不受影响
                if (exoPlayer.isPlaying) {
                    selectionMonitor.onPositionTick(
                        enginePosMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                        selectionActive = selectionStartMs != null && selectionEndMs != null,
                        continuousLoop = continuousLoop,
                        startMs = selectionStartMs ?: 0L,
                        endMs = selectionEndMs ?: 0L,
                        nowMs = android.os.SystemClock.elapsedRealtime(),
                    )
                }
            }
        }
    }

    /** 交互事务后立即回读（双通道之二，架构 6.3）。 */
    private fun emitTick() {
        if (!isPrepared) return
        val enginePos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val normalized = normalize(enginePos)
        displayPosMs = normalized
        _events.tryEmit(PlayerEvent.PositionTick(enginePosMs = enginePos, normalizedPosMs = normalized))
        _events.tryEmit(PlayerEvent.BufferedUpTo(exoPlayer.bufferedPosition.coerceAtLeast(0L)))
    }

    /** 归一化（I1）：仅连续循环模式换算圈内偏移；其余直读引擎值。 */
    private fun normalize(enginePosMs: Long): Long {
        val start = selectionStartMs ?: return enginePosMs
        val end = selectionEndMs ?: return enginePosMs
        if (!continuousLoop || start >= end) return enginePosMs
        return PositionNormalizer.normalize(enginePosMs, start, end)
    }

    private fun onSelectionHit(resetToMs: Long) {
        // I2：暂停 + 复位 A'，一次练习结束
        exoPlayer.pause()
        exoPlayer.seekTo(resetToMs)
        setPhase(PlayerPhase.READY, isPlaying = false)
        _events.tryEmit(PlayerEvent.SelectionHit(resetToMs))
        emitTick()
    }

    private fun setPhase(newPhase: PlayerPhase, isPlaying: Boolean = exoPlayer.isPlaying) {
        // 倒计时期间锁定 COUNTDOWN（引擎 isPlaying 回弹不应覆盖遮罩态）
        val effective = if (countdown.isActive && newPhase != PlayerPhase.ERROR) PlayerPhase.COUNTDOWN else newPhase
        // phase 或 isPlaying 任一变化都必须上报：阶段不变但起播/暂停翻转（onIsPlayingChanged）
        // 若被滤掉，UI 的 immersive/controlVisible 判定将失真（播放中被全屏控制面板遮挡）
        if (effective != phase || isPlaying != reportedIsPlaying) {
            phase = effective
            reportedIsPlaying = isPlaying
            _events.tryEmit(PlayerEvent.StateChanged(effective, isPlaying))
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        val mapped: PlayerError = when (error) {
            is ExoPlaybackException -> when (error.type) {
                ExoPlaybackException.TYPE_SOURCE -> PlayerError(
                    PlayerErrorCode.IO_ERROR,
                    "视频文件不可读或已失效",
                    recoverable = true,
                )
                ExoPlaybackException.TYPE_RENDERER -> PlayerError(
                    PlayerErrorCode.DECODE_FAILED,
                    "无法解码该视频",
                    recoverable = true,
                )
                else -> PlayerError(PlayerErrorCode.UNKNOWN, "播放器异常：${error.message ?: "未知错误"}", recoverable = false)
            }
            else -> PlayerError(PlayerErrorCode.UNKNOWN, "播放器异常：${error.message ?: "未知错误"}", recoverable = false)
        }
        phase = PlayerPhase.ERROR
        _events.tryEmit(PlayerEvent.StateChanged(PlayerPhase.ERROR, isPlaying = false))
        _events.tryEmit(PlayerEvent.Failed(mapped))
    }

    private fun clampSeek(positionMs: Long): Long {
        val upper = if (durationMs > 0L) (durationMs - 1).coerceAtLeast(0L) else Long.MAX_VALUE
        return positionMs.coerceIn(0L, upper)
    }

    /** 源切换时保持的对外位置 = 当前归一化显示位 + seek 容差吸收（引擎落点由 READY 后恢复）。 */
    private fun restoreDisplayPosition(): Long = displayPosMs

    companion object {
        private const val POSITION_TICK_MS = 250L
        private const val LOAD_TIMEOUT_MS = 10_000L
        private const val SOURCE_SWITCH_TIMEOUT_MS = 8_000L
    }
}

/**
 * 渲染器工厂：默认工厂（硬解失败降软解）基础上，把音频渲染器替换为挂载
 * [AudioDelayProcessor] 的 DefaultAudioSink 实例（setAudioProcessors 追加进内建链，
 * 与 Sonic 变速处理器共存，R-13）；视频渲染器替换为 [VideoDelayRenderer]（FR-08 负值落地）。
 */
private class AudioDelayRenderersFactory(
    private val appContext: Context,
    private val delayProcessor: AudioDelayProcessor,
    private val videoDelayProvider: () -> Long,
) : RenderersFactory {

    private val base = DefaultRenderersFactory(appContext)
        .setEnableDecoderFallback(true)

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textOutput: TextOutput,
        metadataOutput: MetadataOutput,
    ): Array<Renderer> = base
        .createRenderers(
            eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textOutput,
            metadataOutput,
        )
        .map { renderer: Renderer ->
            when (renderer) {
                is MediaCodecAudioRenderer -> MediaCodecAudioRenderer(
                    appContext,
                    MediaCodecSelector.DEFAULT,
                    /* enableDecoderFallback= */ true,
                    eventHandler,
                    audioRendererEventListener,
                    DefaultAudioSink.Builder(appContext)
                        .setAudioProcessors(arrayOf(delayProcessor))
                        .build(),
                )
                is MediaCodecVideoRenderer -> VideoDelayRenderer(
                    context = appContext,
                    mediaCodecSelector = MediaCodecSelector.DEFAULT,
                    allowedJoiningTimeMs = DEFAULT_ALLOWED_JOINING_TIME_MS,
                    enableDecoderFallback = true,
                    eventHandler = eventHandler,
                    eventListener = videoRendererEventListener,
                    maxDroppedFramesToNotify = DEFAULT_MAX_DROPPED_FRAMES_TO_NOTIFY,
                    frameDelayUs = videoDelayProvider,
                )
                else -> renderer
            }
        }
        .toTypedArray()

    companion object {
        /** 与 DefaultRenderersFactory 默认一致（MediaCodecVideoRenderer 构造参数）。 */
        private const val DEFAULT_ALLOWED_JOINING_TIME_MS = 5_000L
        private const val DEFAULT_MAX_DROPPED_FRAMES_TO_NOTIFY = 10
    }
}

/**
 * 视频延迟渲染器（FR-08 负值实现）：把渲染时钟整体前移 [frameDelayUs]，
 * 帧呈现时刻随之推迟等量（等效音频提前），与 AudioDelayProcessor 正值互补。
 * 覆盖 render 的 positionUs：渲染器认为播放位置比实际早 delayUs，等待/丢弃判定整体平移，
 * 画面恒比音频晚 delayUs；0 = 直通（默认）。渲染线程读取，callback 可跨线程。
 */
private class VideoDelayRenderer(
    context: Context,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    maxDroppedFramesToNotify: Int,
    private val frameDelayUs: () -> Long,
) : MediaCodecVideoRenderer(
    context,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs - frameDelayUs(), elapsedRealtimeUs)
    }
}