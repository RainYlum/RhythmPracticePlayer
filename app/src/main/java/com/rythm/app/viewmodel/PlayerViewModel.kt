package com.rythm.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rythm.app.data.repository.BookmarkRepository
import com.rythm.app.data.repository.PreferenceRepository
import com.rythm.app.data.repository.VideoLibraryRepository
import com.rythm.app.data.repository.VideoRepository
import com.rythm.app.data.store.ThumbnailStore
import com.rythm.app.domain.policy.FrameStepPolicy
import com.rythm.app.domain.policy.LoopPolicy
import com.rythm.app.domain.policy.LoopValidation
import com.rythm.app.domain.policy.StepRequest
import com.rythm.app.model.FrameMark
import com.rythm.app.model.HistoryEntry
import com.rythm.app.model.PlaybackSpeed
import com.rythm.app.model.PlaybackUiState
import com.rythm.app.model.PlayerError
import com.rythm.app.model.PlayerErrorCode
import com.rythm.app.model.PlayerPhase
import com.rythm.app.player.PlayerEvent
import com.rythm.app.player.VideoPlayerManager
import com.rythm.app.util.MarkCaptionWindow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 播放页唯一状态源（架构 5.2 / 详细设计 3.4）：事件折叠 + 意图方法全集。
 *
 * 关键语义（与架构/详细设计逐条对齐）：
 * - 事件折叠：player.events → StateFlow<PlaybackUiState>，plays 依赖引擎事件回读（双通道，架构 6.3）；
 * - 位置归一化：引擎 PositionTick 已带归一化值（I1），VM 只消费 normalizedPosMs；
 * - I8 播放起点：选区开启 → 无条件 S = A'（FR-04.11）；选区关闭 → S = 当前帧；ENDED → S = 0（FR-02.5）；
 * - 倒计时（FR-10）：countdownSec>0 走 startCountdown(S)，=0 直接 play（FR-10.1 配置持久化）；
 * - 历史：loadVideo 恢复（isUriReadable 不可读 → 级联清理 + ERROR）；onStop 保存归一化位置（FR-07）；
 * - 外推基准：PositionTick/变速/seek 时重置 tickBase*（架构 6.3.1，AC-02.3 由 UI 每帧外推）。
 */
class PlayerViewModel(
    private val videoRepository: VideoRepository,
    private val preferenceRepository: PreferenceRepository,
    private val videoLibraryRepository: VideoLibraryRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val thumbStore: ThumbnailStore,
    private val playerManagerFactory: () -> VideoPlayerManager,
    private val loopPolicy: LoopPolicy = LoopPolicy(),
    private val stepPolicy: FrameStepPolicy = FrameStepPolicy(),
    /** 注入时钟（单测可伪造；生产用 System.currentTimeMillis，仅做 UI 外推与节流基准）。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState
    /** 帧标记沉浸字幕（FR-06.6，v1.5）：仅沉浸播放态派生，暂停/退出沉浸即清空（详细设计 5.7）。 */
    private val _marks = MutableStateFlow<List<FrameMark>>(emptyList())
    val activeCaptions: StateFlow<List<String>> = combine(_uiState, _marks) { state, marks ->
        if (state.immersive) MarkCaptionWindow.activeCaptions(state.displayPositionMs, marks) else emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var player: VideoPlayerManager? = null
    private var eventJob: Job? = null
    private var marksJob: Job? = null
    private var frameRate: Float? = null
    private var lastScrubAt = 0L
    /** 单次练习内 AB 区间会话记忆：AB 按钮关闭时记录、再次开启完整恢复；「清除」按钮/换视频时清空。 */
    private var lastLoopStartMs: Long? = null
    private var lastLoopEndMs: Long? = null

    // ==== 视频装载 / 历史恢复（FR-07，详细设计 4.4） ====

    /**
     * 装载视频并恢复历史。
     * @param restore true = 恢复历史配置（位置/倍速/选区/倒计时/延迟）；false = 全新播放。
     */
    fun loadVideo(uri: Uri, restore: Boolean = true) {
        val uriString = uri.toString()
        if (player != null && _uiState.value.videoUri == uriString) return
        releasePlayer()
        val manager = playerManagerFactory()
        player = manager
        collectEvents(manager)
        resetSessionState(uriString)

        viewModelScope.launch {
            videoRepository.takePersistablePermission(uri)
            // P2-9：授权校验与配置读取串行前置；元数据并行（失败不阻塞播放）
            val readable = videoRepository.isUriReadable(uri)
            if (!readable) {
                // 授权失效（详细设计 7：清理条目 + 提示重选，I6 级联）
                videoLibraryRepository.deleteVideo(uriString)
                _uiState.update {
                    it.copy(
                        phase = PlayerPhase.ERROR,
                        isPlaying = false,
                        error = PlayerError(PlayerErrorCode.IO_ERROR, "文件不可读或已失效，已从视频库移除", recoverable = true),
                    )
                }
                return@launch
            }
            viewModelScope.launch {
                runCatching { videoRepository.readMetadata(uri) }
                    .onSuccess { meta ->
                        frameRate = meta.frameRate
                        _uiState.update { it.copy(durationMs = meta.durationMs.coerceAtLeast(it.durationMs)) }
                    }
                // 失败忽略：duration 由引擎 READY 回读兜底，帧率降 30fps 近似（FR-05.3）
            }
            val settings = preferenceRepository.settings.first()
            val history = if (restore) preferenceRepository.loadHistory(uriString) else null
            val speed = history?.speed ?: settings.defaultSpeed
            val countdownSec = history?.countdownSec ?: settings.countdownSec
            val positionMs = (history?.positionMs ?: 0L).coerceAtLeast(0L)
            val audioOffsetMs = history?.audioOffsetMs ?: 0
            manager.load(
                uri = uri,
                initialPositionMs = positionMs,
                speed = speed,
                audioOffsetMs = audioOffsetMs,
                // AB 点/连续循环不持久化：仅单次练习内保留，退出至视频库后需重新设置
                loopStartMs = null,
                loopEndMs = null,
                continuousLoop = false,
            )
            _uiState.update {
                it.copy(
                    playbackSpeed = speed,
                    countdownSec = countdownSec,
                    currentPositionMs = positionMs,
                    displayPositionMs = positionMs,
                    audioOffsetMs = audioOffsetMs,
                )
            }
            videoLibraryRepository.updateLastOpened(uriString, System.currentTimeMillis())
        }
    }

    private fun releasePlayer() {
        eventJob?.cancel()
        marksJob?.cancel()
        player?.release()
        player = null
        frameRate = null
    }

    private fun resetSessionState(uriString: String) {
        // AB 会话记忆仅单次练习内有效：换视频即清空
        lastLoopStartMs = null
        lastLoopEndMs = null
        _uiState.value = PlaybackUiState(
            videoUri = uriString,
            countdownSec = 3,
            phase = PlayerPhase.LOADING,
        )
        _marks.value = emptyList()
        marksJob = viewModelScope.launch {
            bookmarkRepository.listFlow(uriString).collect { _marks.value = it }
        }
    }

    // ==== 事件折叠（架构 6.3：双通道 → 单点状态） ====

    private fun collectEvents(manager: VideoPlayerManager) {
        eventJob = viewModelScope.launch {
            manager.events.collect { event ->
                when (event) {
                    is PlayerEvent.StateChanged -> onStateChanged(event)
                    is PlayerEvent.PositionTick -> onPositionTick(event)
                    is PlayerEvent.BufferedUpTo -> _uiState.update { it.copy(bufferedPositionMs = event.positionMs) }
                    is PlayerEvent.SelectionHit -> onSelectionHit(event)
                    is PlayerEvent.CountdownProgress -> _uiState.update { it.copy(countdownRemainingMs = event.remainingMs) }
                    is PlayerEvent.CountdownFinished -> onCountdownFinished(event)
                    is PlayerEvent.OrientationChanged -> _uiState.update { it.copy(videoRotationDeg = event.rotationDeg) }
                    is PlayerEvent.Failed -> _uiState.update { it.copy(error = event.error) }
                }
            }
        }
    }

    private fun onStateChanged(event: PlayerEvent.StateChanged) {
        val snap = player?.getSnapshot()
        _uiState.update {
            it.copy(
                phase = event.phase,
                isPlaying = event.isPlaying,
                isImmersive = event.isPlaying && event.phase != PlayerPhase.COUNTDOWN,
                durationMs = snap?.durationMs ?: it.durationMs,
            )
        }
    }

    private fun onPositionTick(event: PlayerEvent.PositionTick) {
        _uiState.update {
            it.copy(
                currentPositionMs = event.normalizedPosMs,
                displayPositionMs = event.normalizedPosMs,
                // 外推基准重置（架构 6.3.1）：每次引擎确认位置后校准
                tickBasePositionMs = event.normalizedPosMs,
                tickBaseElapsedMs = clock(),
                tickBaseSpeed = it.playbackSpeed,
            )
        }
    }

    private fun onSelectionHit(event: PlayerEvent.SelectionHit) {
        // I2：一次练习结束 → 复位 A' + 退出沉浸（isImmersive 由 isPlaying 折叠保持 false）
        _uiState.update {
            it.copy(
                currentPositionMs = event.resetToMs,
                displayPositionMs = event.resetToMs,
                tickBasePositionMs = event.resetToMs,
                tickBaseElapsedMs = clock(),
            )
        }
    }

    private fun onCountdownFinished(event: PlayerEvent.CountdownFinished) {
        // 倒计时结束：遮罩态解除；位置 = S（引擎已对齐到 fromMs，PositionTick 随后校准）
        _uiState.update {
            it.copy(
                countdownRemainingMs = 0,
                currentPositionMs = event.fromMs,
                displayPositionMs = event.fromMs,
                tickBasePositionMs = event.fromMs,
                tickBaseElapsedMs = clock(),
            )
        }
    }

    // ==== 意图方法（对齐架构 5.2 清单） ====

    /** 播放（FR-04.10：统一进入倒计时预听；countdownSec=0 直接起播）。 */
    fun play() {
        val s = _uiState.value
        if (s.isCountdown || s.phase != PlayerPhase.READY && s.phase != PlayerPhase.ENDED) return
        val p = player ?: return
        // I8 起点：ENDED → 0；选区开启 → 无条件 A'；选区关闭 → 当前帧
        val start = when {
            s.phase == PlayerPhase.ENDED -> 0L
            s.loopStartMs != null -> s.loopStartMs
            else -> s.currentPositionMs
        } ?: s.currentPositionMs
        if (s.countdownSec == 0) {
            if (start != s.currentPositionMs) p.seekTo(start, exact = true)
            p.play()
            _uiState.update { it.copy(error = null) }
        } else {
            p.startCountdown(start)
            _uiState.update { it.copy(error = null) }
        }
    }

    /** 倒计时预听入口（与 play 同语义，COUNTDOWN 态下等价取消）。 */
    fun startCountdown() = play()

    /** 暂停（COUNTDOWN 态走取消，回 S；其余直接暂停）。 */
    fun pause() {
        val p = player ?: return
        if (_uiState.value.isCountdown) {
            p.cancelCountdown()
        } else {
            p.pause()
        }
    }

    /** 取消倒计时（详设 4.3 ⑧：pause + seekTo(S) + 回 CONTROL）。 */
    fun cancelCountdown() {
        player?.cancelCountdown()
    }

    /** 双击退出沉浸（FR-02.7）：暂停 + 还原控制 UI。 */
    fun exitImmersive() = pause()

    /** 暂停态精确定位（步进/跳转标记）。 */
    fun seekTo(positionMs: Long) {
        val s = _uiState.value
        if (s.phase != PlayerPhase.READY && s.phase != PlayerPhase.ENDED) return
        val upper = if (s.durationMs > 0L) (s.durationMs - 1).coerceAtLeast(0L) else Long.MAX_VALUE
        player?.seekTo(positionMs.coerceIn(0L, upper), exact = true)
    }

    /** 拖动实时预览（FR-05.1）：40ms 节流 + 精确 seek（exact=true），拖动中逐帧反馈便于找帧，松手 commitScrub 精确定位。 */
    fun scrubTo(targetMs: Long) {
        val now = clock()
        if (now - lastScrubAt < SCRUB_THROTTLE_MS) return
        lastScrubAt = now
        seekTo(targetMs, exact = true)
    }

    /** 拖动松手：精确定位（交互事务立即回读）。 */
    fun commitScrub(targetMs: Long) = seekTo(targetMs)

    private fun seekTo(positionMs: Long, exact: Boolean) {
        val s = _uiState.value
        if (s.phase != PlayerPhase.READY && s.phase != PlayerPhase.ENDED) return
        val upper = if (s.durationMs > 0L) (s.durationMs - 1).coerceAtLeast(0L) else Long.MAX_VALUE
        player?.seekTo(positionMs.coerceIn(0L, upper), exact)
    }

    /** 点按步进（FR-05.2/05.5）：双向契约经 FrameStepPolicy 计算目标。 */
    fun stepFwd(stepMs: Long = FrameStepPolicy.STEP_100MS) = step(+stepMs)

    fun stepBack(stepMs: Long = FrameStepPolicy.STEP_100MS) = step(-stepMs)

    private fun step(deltaMs: Long) {
        val s = _uiState.value
        if (s.phase != PlayerPhase.READY) return
        val decision = stepPolicy.planStep(
            request = StepRequest(baseMs = s.currentPositionMs, deltaMs = deltaMs, frameRate = frameRate),
            lastActualMs = s.currentPositionMs,
        )
        // approximate 仅影响 UI 提示粒度不精确（FR-05.3），定位目标始终执行
        player?.seekTo(decision.targetMs, exact = true)
    }

    /** 六档倍速（I4，FR-03.1）：即时生效 + 外推基准重置。 */
    fun setSpeed(speed: PlaybackSpeed) {
        player?.setSpeed(speed)
        _uiState.update {
            it.copy(
                playbackSpeed = speed,
                tickBaseSpeed = speed,
                tickBasePositionMs = it.currentPositionMs,
                tickBaseElapsedMs = clock(),
            )
        }
    }

    /** 设置 A 点（架构 6.2）：覆盖旧 A、清除旧 B，回到 WAIT_B；引擎侧选区同步失效。 */
    fun setLoopA() {
        val s = _uiState.value
        if (s.phase != PlayerPhase.READY || s.durationMs <= 0L) return
        val a = s.currentPositionMs.coerceIn(0L, s.durationMs)
        val hadSelection = s.loopEnabled || s.continuousLoop
        _uiState.update { it.copy(loopStartMs = a, loopEndMs = null, loopEnabled = false, continuousLoop = false, error = null) }
        if (hadSelection) {
            player?.enableContinuousLoop(false)
            player?.clearSelection()
        }
    }

    /** 设置 B 点（FR-04）：LoopPolicy 校验（B>A、≥max(2帧,100ms)、≤时长）。 */
    fun setLoopB() {
        val s = _uiState.value
        if (s.phase != PlayerPhase.READY) return
        val a = s.loopStartMs
        if (a == null) {
            _uiState.update { it.copy(error = PlayerError(PlayerErrorCode.LOOP_INVALID, "请先设置 A 点", recoverable = true)) }
            return
        }
        when (val result = loopPolicy.validate(a, s.currentPositionMs, s.durationMs)) {
            is LoopValidation.Invalid -> _uiState.update {
                it.copy(error = PlayerError(PlayerErrorCode.LOOP_INVALID, reasonText(result.reason), recoverable = true))
            }
            is LoopValidation.Valid -> {
                _uiState.update { it.copy(loopStartMs = result.startMs, loopEndMs = result.endMs, loopEnabled = true, error = null) }
                player?.setSelection(result.startMs, result.endMs)
            }
        }
    }

    /**
     * 一键 AB 设点（开关注销，单次练习内区间保留）：未设置 → 优先完整恢复本次练习上次关闭时记忆的
     * AB 区间；无记忆则默认以当前帧为 A，延后 [QUICK_LOOP_SPAN_MS]（30s）为 B（超时长 clamp 到末尾，
     * 不满足最短区间时仅报错不改选区）；已设置 → 关闭并记忆当前区间供再次开启恢复（「清除」为彻底清除）。
     */
    fun setQuickLoop() {
        val s = _uiState.value
        if (s.phase != PlayerPhase.READY || s.durationMs <= 0L) return
        // 已设置（含仅设 A 的 WAIT_B）→ 关闭：区间记入会话记忆供再次开启恢复
        if (s.loopStartMs != null) {
            lastLoopStartMs = s.loopStartMs
            lastLoopEndMs = s.loopEndMs
            clearSelectionInternal()
            return
        }
        val a: Long
        val b: Long
        val lastStart = lastLoopStartMs
        val lastEnd = lastLoopEndMs
        if (lastStart != null && lastEnd != null && lastEnd > lastStart) {
            // 上次关闭时记忆的区间 → 完整恢复，不以当前帧重设
            a = lastStart
            b = lastEnd
        } else {
            // 默认：当前帧为 A 点
            a = s.currentPositionMs.coerceIn(0L, s.durationMs)
            b = (a + QUICK_LOOP_SPAN_MS).coerceAtMost(s.durationMs)
        }
        when (val result = loopPolicy.validate(a, b, s.durationMs)) {
            is LoopValidation.Invalid -> _uiState.update {
                it.copy(error = PlayerError(PlayerErrorCode.LOOP_INVALID, reasonText(result.reason), recoverable = true))
            }
            is LoopValidation.Valid -> {
                _uiState.update { it.copy(loopStartMs = result.startMs, loopEndMs = result.endMs, loopEnabled = true, error = null) }
                player?.setSelection(result.startMs, result.endMs)
            }
        }
    }

    /** 清除选区（I2 单次模式无需重建源；连续循环时先关循环再清）。彻底清除：同时清空会话记忆，下次 AB 开启走默认当前帧。 */
    fun clearSelection() {
        lastLoopStartMs = null
        lastLoopEndMs = null
        clearSelectionInternal()
    }

    /** 清除选区（引擎侧 + state），不动会话记忆（AB 按钮关闭路径复用）。 */
    private fun clearSelectionInternal() {
        val s = _uiState.value
        if (s.continuousLoop) player?.enableContinuousLoop(false)
        player?.clearSelection()
        _uiState.update {
            it.copy(loopStartMs = null, loopEndMs = null, loopEnabled = false, continuousLoop = false, error = null)
        }
    }

    /** 连续循环开关（FR-04.9）：仅选区激活时可切换。 */
    fun toggleContinuousLoop() {
        val s = _uiState.value
        if (!s.loopEnabled) return
        val target = !s.continuousLoop
        player?.enableContinuousLoop(target)
        _uiState.update { it.copy(continuousLoop = target) }
    }

    /** A/B 微调（FR-04.8，微调按钮）：±100ms 由 UI 换算 deltaMs 传入；调整后当前帧精确跟随 A 点。 */
    fun nudgeLoopA(deltaMs: Long) = nudgeLoopAInternal(deltaMs, exact = true)

    /** 拖动 A 手柄实时跟随（FR-04.8）：exact=false 就近关键帧，拖动流畅；精确 seek 高频拖动会积压导致滞后更严重。 */
    fun nudgeLoopADrag(deltaMs: Long) = nudgeLoopAInternal(deltaMs, exact = false)

    private fun nudgeLoopAInternal(deltaMs: Long, exact: Boolean) {
        val s = _uiState.value
        val a = s.loopStartMs ?: return
        // 上界：选区激活 → B−最短区间；WAIT_B（B 未定）→ 时长（临时登记的 B 不约束 A）
        val upper = if (s.loopEnabled) {
            ((s.loopEndMs ?: a) - MIN_LOOP_MS).coerceAtLeast(0L)
        } else {
            (s.durationMs - MIN_LOOP_MS).coerceAtLeast(0L)
        }
        val newA = (a + deltaMs).coerceIn(0L, upper)
        if (newA == a) return
        // WAIT_B 态下 B 是跟随 A 的占位（临时登记），上移时重新派生；选区激活时保留真实 B 不动
        val newB = if (s.loopEnabled) {
            s.loopEndMs ?: newA
        } else {
            (newA + MIN_LOOP_MS).coerceAtMost(s.durationMs.coerceAtLeast(newA + 1))
        }
        applyLoopPan(newA, newB)
        seekTo(newA, exact) // 当前帧跟随 A（FR-04.8）
    }

    /** B 微调（微调按钮）：调整后当前帧精确跟随 B 点。 */
    fun nudgeLoopB(deltaMs: Long) = nudgeLoopBInternal(deltaMs, exact = true)

    /** 拖动 B 手柄实时跟随：exact=false 就近关键帧，拖动流畅；精确 seek 高频拖动会积压导致滞后更严重。 */
    fun nudgeLoopBDrag(deltaMs: Long) = nudgeLoopBInternal(deltaMs, exact = false)

    private fun nudgeLoopBInternal(deltaMs: Long, exact: Boolean) {
        val s = _uiState.value
        val a = s.loopStartMs ?: return
        val b = s.loopEndMs ?: return
        val lower = (a + MIN_LOOP_MS).coerceAtMost(s.durationMs.coerceAtLeast(a + 1))
        val upper = if (s.durationMs > 0L) s.durationMs else Long.MAX_VALUE
        val newB = (b + deltaMs).coerceIn(lower, upper)
        if (newB == b) return
        applyLoopPan(a, newB)
        seekTo(newB, exact) // 当前帧跟随 B（FR-04.8）
    }

    private fun applyLoopPan(a: Long, b: Long) {
        val s = _uiState.value
        if (s.loopEnabled) {
            _uiState.update { it.copy(loopStartMs = a, loopEndMs = b) }
            player?.setSelection(a, b)
        } else {
            // WAIT_B 态仅微调起止登记（还差 B 点，不激活选区）
            _uiState.update { it.copy(loopStartMs = a, loopEndMs = b) }
        }
    }

    /** 视频输出 Surface 绑定（UI 层 SurfaceView 回调；null = 解绑）。UI 层不接触 media3。 */
    fun attachVideoSurface(surface: android.view.Surface?) {
        player?.setVideoSurface(surface)
    }

    /** 音频延迟校正（FR-08）：±200ms、5ms 步进（UI 步进）；正值=音频延后，负值=音频提前（视频延迟）。 */
    fun setAudioOffsetMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-AudioDelayLimitMs, AudioDelayLimitMs)
        val effective = player?.setAudioOffsetMs(clamped) ?: return
        _uiState.update { it.copy(audioOffsetMs = effective) }
    }

    /** 倒计时预听时长配置（FR-10.1）：0/3/5s，随历史持久化。 */
    fun setCountdownSec(sec: Int) {
        val s = _uiState.value
        val clamped = sec.coerceIn(0, MaxCountdownSecSec)
        _uiState.update { it.copy(countdownSec = clamped) }
        viewModelScope.launch { preferenceRepository.setCountdownSec(clamped) }
        if (s.isCountdown && clamped == 0) player?.cancelCountdown()
    }

    /** 帧标记（FR-06）：当前归一化位置 + 帧画面缩略图（≤320px，R-12）。 */
    fun addFrameMark(text: String) {
        val s = _uiState.value
        val uri = s.videoUri ?: return
        val positionMs = s.currentPositionMs
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            val file = thumbStore.newMarkFile(id)
            val bitmap = player?.captureFrameAt(positionMs, MarkThumbMaxWidth)
            val saved = bitmap != null && thumbStore.saveJpeg(bitmap, file, MarkThumbMaxWidth)
            bookmarkRepository.add(
                uri,
                FrameMark(
                    id = id,
                    timeMs = positionMs,
                    text = text,
                    thumbPath = if (saved) file.absolutePath else "",
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    // ==== 生命周期（架构 5.2：ON_STOP = 退倒计时 + 保存历史；onCleared = 释放引擎） ====

    /** ON_STOP（UI 生命周期回调）：倒计时中先取消；保存归一化位置/配置（FR-07）。 */
    fun onStop() {
        if (_uiState.value.isCountdown) player?.cancelCountdown()
        saveHistory()
    }

    /** 保存播放历史（归一化位置 + 倍速 + 倒计时 + 音频延迟，FR-07）：AB 点/连续循环仅单次练习内有效，不持久化。 */
    fun saveHistory() {
        val p = player ?: return
        val s = _uiState.value
        val uri = s.videoUri ?: return
        if (s.phase == PlayerPhase.IDLE || s.phase == PlayerPhase.ERROR) return
        val snap = p.getSnapshot()
        viewModelScope.launch {
            preferenceRepository.saveHistory(
                HistoryEntry(
                    uri = uri,
                    positionMs = snap.normalizedPositionMs,
                    speed = snap.speed,
                    loopStartMs = null,
                    loopEndMs = null,
                    loopEnabled = false,
                    continuousLoop = false,
                    countdownSec = s.countdownSec,
                    audioOffsetMs = s.audioOffsetMs,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
    }

    private fun reasonText(reason: LoopValidation.Reason): String = when (reason) {
        LoopValidation.Reason.MISSING_A -> "请先设置 A 点"
        LoopValidation.Reason.B_BEFORE_A -> "B 点需晚于 A 点"
        LoopValidation.Reason.TOO_SHORT -> "区间过短（需 ≥ 100ms）"
    }

    companion object {
        /** 拖动节流（详细设计 3.4.3 高通道；收紧至 40ms 配合精确 seek，拖动逐帧反馈找帧）。 */
        const val SCRUB_THROTTLE_MS = 40L

        /** 选区最短区间（LoopPolicy 默认 minIntervalMs=100，与帧时长 2 帧取大者）。 */
        const val MIN_LOOP_MS = 100L

        /** 一键 AB 设点跨度（FR-04 快捷：A 延后 30s 为 B）。 */
        const val QUICK_LOOP_SPAN_MS = 30_000L

        /** 帧标记缩略图宽（R-12，ThumbnailStore 默认）。 */
        const val MarkThumbMaxWidth = 320

        /** 倒计时预听时长上限（FR-10.1：0/3/5s）。 */
        const val MaxCountdownSecSec = 5

        /** 音频延迟偏移上限（FR-08）：±200ms、5ms 步进。 */
        const val AudioDelayLimitMs = 200
    }
}