package com.rythm.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.rythm.app.data.repository.BookmarkRepository
import com.rythm.app.data.repository.PreferenceRepository
import com.rythm.app.data.repository.VideoLibraryRepository
import com.rythm.app.data.repository.VideoRepository
import com.rythm.app.data.store.ThumbnailStore
import com.rythm.app.model.AppSettings
import com.rythm.app.model.FrameMark
import com.rythm.app.model.HistoryEntry
import com.rythm.app.model.LibraryItem
import com.rythm.app.model.OnboardingStatus
import com.rythm.app.model.PlaybackSpeed
import com.rythm.app.model.PlayerError
import com.rythm.app.model.PlayerErrorCode
import com.rythm.app.model.PlayerPhase
import com.rythm.app.model.VideoMetadata
import com.rythm.app.player.PlayerEvent
import com.rythm.app.player.PlayerSnapshot
import com.rythm.app.player.VideoPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import java.io.File

/**
 * PlayerViewModel 意图/事件折叠测试（架构 §12 viewmodel 层）。
 * 覆盖：状态机全转移、位置归一化与外推基准、沉浸进出、倒计时、历史恢复（含不可读清理）、
 * I8 播放起点、选区生命周期、音频延迟边界、scrub 节流、步进契约、字幕派生。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private companion object {
        const val URI = "content://media/external/video/media/100"
    }

    private val uri: Uri = mock(Uri::class.java).also {
        Mockito.`when`(it.toString()).thenReturn(URI)
        Mockito.`when`(it.scheme).thenReturn("content")
    }

    private lateinit var manager: FakePlayerManager
    private lateinit var videoRepo: FakeVideoRepository
    private lateinit var prefs: FakePreferenceRepository
    private lateinit var libraryRepo: FakeVideoLibraryRepository
    private lateinit var bookmarkRepo: FakeBookmarkRepository
    private lateinit var thumbStore: ThumbnailStore
    private var nowMs = 1_000_000L
    private lateinit var vm: PlayerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        manager = FakePlayerManager()
        videoRepo = FakeVideoRepository()
        prefs = FakePreferenceRepository()
        libraryRepo = FakeVideoLibraryRepository()
        bookmarkRepo = FakeBookmarkRepository()
        thumbStore = FakeThumbnailStore()
        nowMs = 1_000_000L
        vm = PlayerViewModel(
            videoRepository = videoRepo,
            preferenceRepository = prefs,
            videoLibraryRepository = libraryRepo,
            bookmarkRepository = bookmarkRepo,
            thumbStore = thumbStore,
            playerManagerFactory = { manager },
            clock = { nowMs },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 事件注入 + 驱动主调度器（双通道回读路径）。 */
    private suspend fun TestScope.emit(event: PlayerEvent) {
        manager.eventFlow.emit(event)
        advanceUntilIdle()
    }

    // ==== 装载 / 历史恢复（FR-07，详设 4.4） ====

    @Test
    fun `装载并恢复历史配置_但AB点不持久化不恢复`() = runTest {
        prefs.settings.value = AppSettings(defaultSpeed = PlaybackSpeed.S0_5, countdownSec = 0)
        prefs.history = HistoryEntry(
            uri = URI, positionMs = 12_345, speed = PlaybackSpeed.S0_7,
            loopStartMs = 1_000, loopEndMs = 5_000, loopEnabled = true,
            continuousLoop = true, countdownSec = 5, audioOffsetMs = 30,
        )

        vm.loadVideo(uri, restore = true)
        advanceUntilIdle()

        val load = manager.loadCalls.single()
        assertEquals(12_345L, load.initialPositionMs)
        assertEquals(PlaybackSpeed.S0_7, load.speed)
        assertEquals(30, load.audioOffsetMs)
        // AB 点/连续循环仅单次练习内保留：历史里即使有也不恢复，需重新设置
        assertNull(load.loopStartMs)
        assertNull(load.loopEndMs)
        assertFalse(load.continuousLoop)
        assertEquals(listOf(uri), videoRepo.permissionUris)
        assertEquals(listOf(uri), videoRepo.readabilityChecks)
        assertEquals(listOf(uri), videoRepo.readMetadataUris)
        assertEquals(URI, libraryRepo.lastOpened.single().first)

        val s = vm.uiState.value
        assertEquals(PlaybackSpeed.S0_7, s.playbackSpeed)
        assertEquals(5, s.countdownSec)
        assertEquals(12_345L, s.currentPositionMs)
        assertEquals(12_345L, s.displayPositionMs)
        assertEquals(30, s.audioOffsetMs)
        assertNull(s.loopStartMs)
        assertNull(s.loopEndMs)
        assertFalse(s.loopEnabled)
        assertFalse(s.continuousLoop)
        assertEquals(URI, s.videoUri)
    }

    @Test
    fun `restore 关闭时全新播放不走历史`() = runTest {
        prefs.settings.value = AppSettings(defaultSpeed = PlaybackSpeed.S0_5, countdownSec = 0)
        prefs.history = HistoryEntry(uri = URI, positionMs = 99_999, speed = PlaybackSpeed.S0_9)

        vm.loadVideo(uri, restore = false)
        advanceUntilIdle()

        val load = manager.loadCalls.single()
        assertEquals(0L, load.initialPositionMs)
        assertEquals(PlaybackSpeed.S0_5, load.speed)
        assertEquals(0, load.audioOffsetMs)
        assertNull(load.loopStartMs)
        assertNull(load.loopEndMs)
        assertFalse(load.continuousLoop)

        val s = vm.uiState.value
        assertEquals(0L, s.currentPositionMs)
        assertEquals(PlaybackSpeed.S0_5, s.playbackSpeed)
        assertEquals(0, s.countdownSec)
        assertFalse(s.loopEnabled)
    }

    @Test
    fun `授权失效时级联删除并进入 ERROR`() = runTest {
        videoRepo.readable = false

        vm.loadVideo(uri)
        advanceUntilIdle()

        assertEquals(listOf(URI), libraryRepo.deletedUris)
        assertEquals(PlayerPhase.ERROR, vm.uiState.value.phase)
        assertEquals(PlayerErrorCode.IO_ERROR, vm.uiState.value.error?.code)
        assertTrue(vm.uiState.value.error?.recoverable == true)
        assertTrue(manager.loadCalls.isEmpty())
        assertFalse(vm.uiState.value.isPlaying)
    }

    @Test
    fun `重复装载同视频不重建`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        vm.loadVideo(uri)
        advanceUntilIdle()

        assertEquals(1, manager.loadCalls.size)
        assertFalse(manager.released)
    }

    // ==== 事件折叠（架构 6.3） ====

    @Test
    fun `状态机全转移与沉浸控制进出`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()

        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        var s = vm.uiState.value
        assertEquals(PlayerPhase.READY, s.phase)
        assertFalse(s.isPlaying)
        assertFalse(s.immersive)
        assertTrue(s.controlVisible)
        assertEquals(90_000L, s.durationMs) // duration 由引擎快照回读补充

        emit(PlayerEvent.StateChanged(PlayerPhase.READY, true))
        s = vm.uiState.value
        assertTrue(s.immersive)
        assertFalse(s.controlVisible)

        emit(PlayerEvent.StateChanged(PlayerPhase.BUFFERING, true))
        assertTrue(vm.uiState.value.immersive) // 播放中缓冲保持沉浸（进度圈覆盖）

        emit(PlayerEvent.StateChanged(PlayerPhase.COUNTDOWN, true))
        s = vm.uiState.value
        assertFalse(s.immersive) // 倒计时不算沉浸
        assertTrue(s.controlVisible)
        assertTrue(s.isCountdown)

        emit(PlayerEvent.StateChanged(PlayerPhase.ENDED, false))
        s = vm.uiState.value
        assertEquals(PlayerPhase.ENDED, s.phase)
        assertTrue(s.controlVisible) // FR-02.5：播完显示重播控制
        assertFalse(s.immersive)
    }

    @Test
    fun `PositionTick 消费归一化位置并重置外推基准`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        nowMs = 1_000_000L

        // 连续循环下引擎值 ≠ 归一化值（I1）
        emit(PlayerEvent.PositionTick(enginePosMs = 50_000, normalizedPosMs = 2_000))

        var s = vm.uiState.value
        assertEquals(2_000L, s.currentPositionMs)
        assertEquals(2_000L, s.displayPositionMs)
        assertEquals(2_000L, s.tickBasePositionMs)
        assertEquals(1_000_000L, s.tickBaseElapsedMs)
        assertEquals(PlaybackSpeed.S1_0, s.tickBaseSpeed)

        // 非沉浸不外推
        assertEquals(2_000L, s.extrapolatedDisplayPositionMs(1_000_500))

        // 起播后 1.0x 外推
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, true))
        s = vm.uiState.value
        assertEquals(2_500L, s.extrapolatedDisplayPositionMs(1_000_500))

        // 变速 0.5x：基准重置，外推斜率更新
        vm.setSpeed(PlaybackSpeed.S0_5)
        s = vm.uiState.value
        assertEquals(PlaybackSpeed.S0_5, s.tickBaseSpeed)
        assertEquals(2_000L, s.tickBasePositionMs)
        assertEquals(1_000_000L, s.tickBaseElapsedMs)
        assertEquals(2_250L, s.extrapolatedDisplayPositionMs(1_000_500))
    }

    @Test
    fun `SelectionHit 复位位置并重置外推基准`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, true))
        nowMs = 2_000_000L
        emit(PlayerEvent.PositionTick(enginePosMs = 50_000, normalizedPosMs = 9_000))

        emit(PlayerEvent.SelectionHit(resetToMs = 1_000))

        val s = vm.uiState.value
        assertEquals(1_000L, s.currentPositionMs)
        assertEquals(1_000L, s.displayPositionMs)
        assertEquals(1_000L, s.tickBasePositionMs)
        assertEquals(2_000_000L, s.tickBaseElapsedMs)
    }

    @Test
    fun `倒计时进度与结束事件折叠`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.COUNTDOWN, false))
        emit(PlayerEvent.CountdownProgress(remainingMs = 1_800))
        assertEquals(1_800, vm.uiState.value.countdownRemainingMs)

        nowMs = 3_000_000L
        emit(PlayerEvent.CountdownFinished(fromMs = 4_000))

        val s = vm.uiState.value
        assertEquals(0, s.countdownRemainingMs)
        assertEquals(4_000L, s.currentPositionMs)
        assertEquals(4_000L, s.displayPositionMs)
        assertEquals(3_000_000L, s.tickBaseElapsedMs)
    }

    @Test
    fun `缓冲方向与错误事件折叠`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()

        emit(PlayerEvent.BufferedUpTo(positionMs = 5_000))
        emit(PlayerEvent.OrientationChanged(rotationDeg = 90))
        emit(PlayerEvent.Failed(PlayerError(PlayerErrorCode.DECODE_FAILED, "解码失败", recoverable = false)))

        val s = vm.uiState.value
        assertEquals(5_000L, s.bufferedPositionMs)
        assertEquals(90, s.videoRotationDeg)
        assertEquals(PlayerErrorCode.DECODE_FAILED, s.error?.code)
        assertTrue(s.error?.recoverable == false)
    }

    // ==== 播放 / 倒计时入口（FR-04.10/04.11，I8） ====

    @Test
    fun `play 选区开启时无条件跳 A 点进入倒计时`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(6_000, 6_000))
        vm.setLoopA()
        emit(PlayerEvent.PositionTick(9_000, 9_000))
        vm.setLoopB()
        assertEquals(1, manager.selectionCalls.size)

        vm.play() // countdownSec=3 默认 → startCountdown(A', 前导 3s)

        assertEquals(listOf(6_000L to 3_000L), manager.countdownCalls)
        assertEquals(0, manager.seekCalls.size)
        assertEquals(0, manager.playCalls)
    }

    @Test
    fun `play 选区关闭且倒计时关闭时直接起播`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        vm.setCountdownSec(0)
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(3_000, 3_000))

        vm.play()

        assertEquals(1, manager.playCalls)
        assertTrue(manager.seekCalls.isEmpty()) // start == 当前帧不做无谓 seek
        assertTrue(manager.countdownCalls.isEmpty())
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `I8 选区开启时当前位置在 A 前仍无条件先跳 A 点`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        vm.setCountdownSec(0)
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(5_000, 5_000))
        vm.setLoopA() // A' = 5000
        emit(PlayerEvent.PositionTick(8_000, 8_000))
        vm.setLoopB() // B' = 8000
        emit(PlayerEvent.PositionTick(1_000, 1_000)) // 当前帧在 A 前

        vm.play()

        assertEquals(listOf(5_000L to true), manager.seekCalls)
        assertEquals(1, manager.playCalls)
    }

    @Test
    fun `I8 选区开启时当前位置在区间内仍无条件先跳 A 点`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        vm.setCountdownSec(0)
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(5_000, 5_000))
        vm.setLoopA()
        emit(PlayerEvent.PositionTick(8_000, 8_000))
        vm.setLoopB()
        emit(PlayerEvent.PositionTick(6_500, 6_500)) // 当前帧在区间内

        vm.play()

        assertEquals(listOf(5_000L to true), manager.seekCalls)
        assertEquals(1, manager.playCalls)
    }

    @Test
    fun `I8 选区开启时当前位置在 B 后仍无条件先跳 A 点`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        vm.setCountdownSec(3) // 倒计时路径：跳转由引擎侧完成（AC-04.9）
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(5_000, 5_000))
        vm.setLoopA()
        emit(PlayerEvent.PositionTick(8_000, 8_000))
        vm.setLoopB()
        emit(PlayerEvent.PositionTick(20_000, 20_000)) // 当前帧在 B 后

        vm.play()

        assertEquals(listOf(5_000L to 3_000L), manager.countdownCalls)
        assertTrue(manager.seekCalls.isEmpty()) // S==A' 交引擎，VM 层不做 seek
    }

    @Test
    fun `I8 选区开启且当前位置恰在 A 时不产生可见跳变`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        vm.setCountdownSec(0)
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(5_000, 5_000))
        vm.setLoopA()
        emit(PlayerEvent.PositionTick(8_000, 8_000))
        vm.setLoopB()
        emit(PlayerEvent.PositionTick(5_000, 5_000)) // 恰在 A'（±1 帧内）

        vm.play()

        assertTrue(manager.seekCalls.isEmpty()) // start == 当前帧，无跳转
        assertEquals(1, manager.playCalls)
        assertTrue(manager.countdownCalls.isEmpty())
    }

    @Test
    fun `play ENDED 态复位到 0 重播`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        vm.setCountdownSec(0)
        emit(PlayerEvent.StateChanged(PlayerPhase.ENDED, false))
        emit(PlayerEvent.PositionTick(85_000, 85_000))

        vm.play()

        assertEquals(listOf(0L to true), manager.seekCalls)
        assertEquals(1, manager.playCalls)
    }

    @Test
    fun `倒计时态 play 与暂停语义`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.COUNTDOWN, true))

        vm.play() // COUNTDOWN 态忽略
        assertTrue(manager.countdownCalls.isEmpty())
        assertTrue(manager.playCalls == 0)

        vm.pause() // 倒计时态暂停 = 取消
        assertEquals(1, manager.cancelCountdownCalls)
        assertEquals(0, manager.pauseCalls)

        emit(PlayerEvent.StateChanged(PlayerPhase.READY, true))
        vm.pause()
        assertEquals(1, manager.pauseCalls)
        vm.exitImmersive() // 双击退出 = 暂停
        assertEquals(2, manager.pauseCalls)
    }

    @Test
    fun `play countdownSec 5 时前导窗口传 5s`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        vm.setCountdownSec(5)
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(3_000, 3_000))

        vm.play()

        // 倒计时 5s → 前导窗口 5000ms（数字从 5 开始倒数，修复 3s 硬编码）
        assertEquals(listOf(3_000L to 5_000L), manager.countdownCalls)
        assertEquals(0, manager.playCalls)
    }

    @Test
    fun `教学完成标记委派到持久化`() = runTest {
        vm.markPlayerTutorialDone()
        vm.markMoreMenuTutorialDone()
        advanceUntilIdle() // 持久化为 viewModelScope.launch 异步写入

        assertEquals(listOf(true), prefs.playerTutorialDoneSet)
        assertEquals(listOf(true), prefs.moreMenuTutorialDoneSet)
    }

    @Test
    fun `onboarding 初始状态三阶段均未完成`() = runTest {
        assertEquals(OnboardingStatus(), vm.onboarding.value)
    }

    @Test
    fun `setCountdownSec 持久化与倒计时中置零取消`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()

        vm.setCountdownSec(5)
        advanceUntilIdle() // 持久化为 viewModelScope.launch 异步写入
        assertEquals(5, vm.uiState.value.countdownSec)
        assertEquals(listOf(5), prefs.countdownSecSet)

        emit(PlayerEvent.StateChanged(PlayerPhase.COUNTDOWN, true))
        vm.setCountdownSec(0) // 倒计时中关闭预听 → 取消
        advanceUntilIdle()
        assertEquals(1, manager.cancelCountdownCalls)

        vm.setCountdownSec(99) // 超上限钳制
        advanceUntilIdle()
        assertEquals(listOf(5, 0, 5), prefs.countdownSecSet)
        assertEquals(5, vm.uiState.value.countdownSec)
    }

    // ==== 定位 / 步进（FR-05） ====

    @Test
    fun `scrubTo 40ms 节流与 commitScrub 精确`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        nowMs = 1_000_000L

        vm.scrubTo(1_000) // (1000, exact=true) 拖动预览精确逐帧
        vm.scrubTo(2_000) // 节流内忽略
        nowMs += 100
        vm.scrubTo(3_000)
        vm.commitScrub(4_000) // 松手精确定位

        assertEquals(
            listOf(1_000L to true, 3_000L to true, 4_000L to true),
            manager.seekCalls,
        )
    }

    @Test
    fun `stepFwd stepBack 经 FrameStepPolicy 计算`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(10_000, 10_000))

        vm.stepFwd() // 目标 = 10_000 + 100
        assertEquals(listOf(10_100L to true), manager.seekCalls)

        emit(PlayerEvent.PositionTick(10_100, 10_100)) // 引擎回读确认落点
        vm.stepBack() // 目标 = 10_100 − 100
        assertEquals(10_000L to true, manager.seekCalls.last())
    }

    @Test
    fun `seekTo 超界钳制到时长内`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))

        vm.seekTo(500_000) // duration=90_000 → 钳制 89_999
        vm.seekTo(-5)

        assertEquals(listOf(89_999L to true, 0L to true), manager.seekCalls)
    }

    // ==== 选区生命周期（I2 / FR-04，架构 6.2） ====

    @Test
    fun `setLoopA 覆盖旧选区回 WAIT_B 并清理引擎选区`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(5_000, 5_000))

        vm.setLoopA()
        var s = vm.uiState.value
        assertEquals(5_000L, s.loopStartMs)
        assertNull(s.loopEndMs)
        assertFalse(s.loopEnabled)

        emit(PlayerEvent.PositionTick(8_000, 8_000))
        vm.setLoopB()
        assertTrue(vm.uiState.value.loopEnabled)
        assertEquals(1, manager.selectionCalls.size)

        // 已有选区时再设 A：旧选区清理 + 引擎同步失效
        emit(PlayerEvent.PositionTick(20_000, 20_000))
        vm.setLoopA()
        s = vm.uiState.value
        assertEquals(20_000L, s.loopStartMs)
        assertNull(s.loopEndMs)
        assertFalse(s.loopEnabled)
        assertEquals(listOf(false), manager.continuousLoopCalls)
        assertEquals(1, manager.clearSelectionCalls)
    }

    @Test
    fun `setLoopB 校验分支`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(5_000, 5_000))

        // 未设 A → MISSING_A
        vm.setLoopB()
        assertEquals(PlayerErrorCode.LOOP_INVALID, vm.uiState.value.error?.code)
        assertTrue(manager.selectionCalls.isEmpty())

        vm.setLoopA()
        // B 早于 A
        emit(PlayerEvent.PositionTick(4_000, 4_000))
        vm.setLoopB()
        assertEquals(PlayerErrorCode.LOOP_INVALID, vm.uiState.value.error?.code)
        // 区间过短（50 < 100ms）
        emit(PlayerEvent.PositionTick(5_050, 5_050))
        vm.setLoopB()
        assertEquals(PlayerErrorCode.LOOP_INVALID, vm.uiState.value.error?.code)
        // B 超出时长
        emit(PlayerEvent.PositionTick(100_000, 100_000))
        vm.setLoopB()
        assertEquals(PlayerErrorCode.LOOP_INVALID, vm.uiState.value.error?.code)
        // 合法
        emit(PlayerEvent.PositionTick(12_000, 12_000))
        vm.setLoopB()
        val s = vm.uiState.value
        assertTrue(s.loopEnabled)
        assertEquals(5_000L, s.loopStartMs)
        assertEquals(12_000L, s.loopEndMs)
        assertNull(s.error)
        assertEquals(listOf(5_000L to 12_000L), manager.selectionCalls)
    }

    @Test
    fun `nudgeLoopA B 边界钳制`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(5_000, 5_000))
        vm.setLoopA() // WAIT_B：a=5000, b=null

        // 无 B 点时不动作
        vm.nudgeLoopB(10_000)
        assertNull(vm.uiState.value.loopEndMs)

        // A 下界：0（下限 100ms 由 B 兜底）
        vm.nudgeLoopA(-10_000)
        var s = vm.uiState.value
        assertEquals(0L, s.loopStartMs)
        assertEquals(100L, s.loopEndMs) // newA + MIN_LOOP_MS

        // A 上移
        vm.nudgeLoopA(+50_000)
        s = vm.uiState.value
        assertEquals(50_000L, s.loopStartMs)
        assertEquals(50_100L, s.loopEndMs)

        // 建立选区后 nudge B
        emit(PlayerEvent.PositionTick(60_000, 60_000))
        vm.setLoopB()
        assertTrue(vm.uiState.value.loopEnabled)

        vm.nudgeLoopB(-100_000)
        assertEquals(50_100L, vm.uiState.value.loopEndMs) // lower = A + 100ms

        vm.nudgeLoopB(+100_000)
        assertEquals(90_000L, vm.uiState.value.loopEndMs) // upper = duration

        // setLoopB 1 次 + nudge 2 次同步引擎
        assertEquals(3, manager.selectionCalls.size)
    }

    @Test
    fun `setQuickLoop 开关注销_关闭后再次开启恢复上次区间`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(5_000, 5_000))

        vm.setQuickLoop() // 未设置 → 默认 A=当前帧，B=A+30s
        var s = vm.uiState.value
        assertEquals(5_000L, s.loopStartMs)
        assertEquals(35_000L, s.loopEndMs)
        assertTrue(s.loopEnabled)
        assertEquals(listOf(5_000L to 35_000L), manager.selectionCalls)

        // 播放/暂停往返不丢失 AB（单次练习内保留）
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, true))
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        assertNotNull(vm.uiState.value.loopStartMs)

        // 已设置再点 → 关闭（区间记入会话记忆）
        vm.setQuickLoop()
        s = vm.uiState.value
        assertNull(s.loopStartMs)
        assertNull(s.loopEndMs)
        assertFalse(s.loopEnabled)
        assertEquals(1, manager.clearSelectionCalls)

        // 位置前移后再开启 → 完整恢复上次区间（不以当前帧重设 A）
        emit(PlayerEvent.PositionTick(60_000, 60_000))
        vm.setQuickLoop()
        s = vm.uiState.value
        assertEquals(5_000L, s.loopStartMs)
        assertEquals(35_000L, s.loopEndMs)
        assertTrue(s.loopEnabled)
        assertEquals(2, manager.selectionCalls.size)

        // 「清除」= 彻底清除（含会话记忆）：再次开启走默认当前帧
        vm.clearSelection()
        emit(PlayerEvent.PositionTick(60_000, 60_000))
        vm.setQuickLoop()
        s = vm.uiState.value
        assertEquals(60_000L, s.loopStartMs)
        assertEquals(90_000L, s.loopEndMs)
        assertTrue(s.loopEnabled)
    }

    @Test
    fun `clearSelection 与连续循环开关`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(5_000, 5_000))
        vm.setLoopA()
        emit(PlayerEvent.PositionTick(8_000, 8_000))
        vm.setLoopB()

        // 未开连续循环时开关无效
        vm.toggleContinuousLoop()
        assertTrue(vm.uiState.value.loopEnabled)
        assertTrue(vm.uiState.value.continuousLoop)
        assertEquals(1, manager.selectionCalls.size)

        // 连续循环开启时 clearSelection：先关引擎循环再清选区
        vm.clearSelection()
        val s = vm.uiState.value
        assertNull(s.loopStartMs)
        assertNull(s.loopEndMs)
        assertFalse(s.loopEnabled)
        assertFalse(s.continuousLoop)
        assertEquals(listOf(true, false), manager.continuousLoopCalls)
        assertEquals(1, manager.clearSelectionCalls)

        // 再次清除（无选区）幂等：不再调引擎
        vm.clearSelection()
        assertEquals(2, manager.clearSelectionCalls)
        assertEquals(listOf(true, false), manager.continuousLoopCalls)
    }

    // ==== 音频延迟（FR-08） ====

    @Test
    fun `setAudioOffsetMs 正负边界钳制回写设定值`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()

        vm.setAudioOffsetMs(500) // 超上限钳 ±200
        assertEquals(listOf(200), manager.audioOffsetCalls)
        assertEquals(200, vm.uiState.value.audioOffsetMs)

        vm.setAudioOffsetMs(-500)
        assertEquals(listOf(200, -200), manager.audioOffsetCalls)
        assertEquals(-200, vm.uiState.value.audioOffsetMs) // 负值 = 音频提前（视频延迟），不再降级

        vm.setAudioOffsetMs(30)
        assertEquals(listOf(200, -200, 30), manager.audioOffsetCalls)
        assertEquals(30, vm.uiState.value.audioOffsetMs)
    }

    // ==== 帧标记 / 字幕（FR-06） ====

    @Test
    fun `activeCaptions 沉浸窗口进出与暂停清空`() = runTest {
        bookmarkRepo.marks.value = listOf(
            FrameMark(id = "m1", timeMs = 10_000, text = "重音"),
            FrameMark(id = "m2", timeMs = 15_000, text = ""), // 空文本不提醒
            FrameMark(id = "m3", timeMs = 60_000, text = "副歌"),
        )
        vm.loadVideo(uri)
        advanceUntilIdle()

        assertTrue(vm.activeCaptions.value.isEmpty()) // 非沉浸不提醒

        emit(PlayerEvent.StateChanged(PlayerPhase.READY, true))
        emit(PlayerEvent.PositionTick(9_000, 9_000))
        assertEquals(listOf("重音"), vm.activeCaptions.value)

        emit(PlayerEvent.PositionTick(14_000, 14_000)) // 窗口外
        assertTrue(vm.activeCaptions.value.isEmpty())

        emit(PlayerEvent.PositionTick(58_500, 58_500))
        assertEquals(listOf("副歌"), vm.activeCaptions.value)

        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false)) // 暂停 → 清空
        assertTrue(vm.activeCaptions.value.isEmpty())

        emit(PlayerEvent.StateChanged(PlayerPhase.READY, true)) // 重播再提醒
        emit(PlayerEvent.PositionTick(10_500, 10_500))
        assertEquals(listOf("重音"), vm.activeCaptions.value)
    }

    @Test
    fun `addFrameMark 登记归一化位置与缩略图联动`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(7_777, 7_777))
        manager.captureResult = mock(Bitmap::class.java)

        vm.addFrameMark("注意节拍")
        advanceUntilIdle()

        val (addedUri, mark) = bookmarkRepo.added.single()
        assertEquals(URI, addedUri)
        assertEquals(7_777L, mark.timeMs)
        assertEquals("注意节拍", mark.text)
        assertEquals(File("C:/tmp/marks/x.jpg").absolutePath, mark.thumbPath)
    }

    @Test
    fun `addFrameMark 抓帧失败时缩略图留空`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(3_000, 3_000))
        manager.captureResult = null // 抓帧失败

        vm.addFrameMark("无图标记")
        advanceUntilIdle()

        assertEquals(3_000L, bookmarkRepo.added.single().second.timeMs)
        assertEquals("", bookmarkRepo.added.single().second.thumbPath)
    }

    // ==== 生命周期（FR-07） ====

    @Test
    fun `onStop 保存引擎归一化历史且AB点不持久化`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.READY, false))
        emit(PlayerEvent.PositionTick(12_345, 12_345))
        vm.setSpeed(PlaybackSpeed.S0_8)
        vm.setCountdownSec(5)
        vm.setAudioOffsetMs(25)
        manager.snapshotValue = manager.snapshotValue.copy(
            normalizedPositionMs = 12_345,
            speed = PlaybackSpeed.S0_8,
            loopStartMs = 1_000,
            loopEndMs = 5_000,
            continuousLoop = true,
        )

        vm.onStop()
        advanceUntilIdle() // 历史写入为异步（viewModelScope.launch）

        val h = prefs.savedHistories.single()
        assertEquals(URI, h.uri)
        assertEquals(12_345L, h.positionMs)
        assertEquals(PlaybackSpeed.S0_8, h.speed)
        assertEquals(5, h.countdownSec)
        assertEquals(25, h.audioOffsetMs)
        // AB 点/连续循环仅单次练习有效：引擎快照里有也不写入历史（退出到视频库后需重设）
        assertNull(h.loopStartMs)
        assertNull(h.loopEndMs)
        assertFalse(h.loopEnabled)
        assertFalse(h.continuousLoop)
    }

    @Test
    fun `onStop 倒计时中先取消再保存`() = runTest {
        vm.loadVideo(uri)
        advanceUntilIdle()
        emit(PlayerEvent.StateChanged(PlayerPhase.COUNTDOWN, true))

        vm.onStop()
        advanceUntilIdle() // 历史写入为异步（viewModelScope.launch）

        assertEquals(1, manager.cancelCountdownCalls)
        assertEquals(1, prefs.savedHistories.size)
    }

    @Test
    fun `ERROR 态不保存历史`() = runTest {
        videoRepo.readable = false
        vm.loadVideo(uri)
        advanceUntilIdle()

        vm.onStop()

        assertTrue(prefs.savedHistories.isEmpty())
    }

    // ==== Fakes ====
    
    /**
     * ThumbnailStore 假实现：避免 mockito stub suspend 函数（matcher 串扰 + 非空返回值
     * 在挂起点被 checkNotNull 触发 NPE），改用 open 类 override。
     */
    private class FakeThumbnailStore : ThumbnailStore(mock(Context::class.java)) {
        var markFile: File = File("C:/tmp/marks/x.jpg")
        var saveResult = true
        val saveCalls = mutableListOf<Pair<Bitmap, File>>()
    
        override fun newMarkFile(markId: String): File = markFile
    
        override suspend fun saveJpeg(bitmap: Bitmap, dest: File, maxWidthPx: Int): Boolean {
            saveCalls += bitmap to dest
            return saveResult
        }
    }
    
    private class FakePlayerManager : VideoPlayerManager {
        data class LoadCall(
            val initialPositionMs: Long,
            val speed: PlaybackSpeed,
            val audioOffsetMs: Int,
            val loopStartMs: Long?,
            val loopEndMs: Long?,
            val continuousLoop: Boolean,
        )

        val eventFlow = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64)
        override val events: Flow<PlayerEvent> = eventFlow

        val loadCalls = mutableListOf<LoadCall>()
        val seekCalls = mutableListOf<Pair<Long, Boolean>>()
        val speedCalls = mutableListOf<PlaybackSpeed>()
        val selectionCalls = mutableListOf<Pair<Long, Long>>()
        val continuousLoopCalls = mutableListOf<Boolean>()
        val countdownCalls = mutableListOf<Pair<Long, Long>>()
        val audioOffsetCalls = mutableListOf<Int>()
        val videoSurfaceCalls = mutableListOf<android.view.Surface?>()
        var playCalls = 0
        var pauseCalls = 0
        var clearSelectionCalls = 0
        var cancelCountdownCalls = 0
        var released = false
        var captureResult: Bitmap? = null
        var snapshotValue = PlayerSnapshot(
            phase = PlayerPhase.READY, isPlaying = false, enginePositionMs = 0L, normalizedPositionMs = 0L,
            durationMs = 90_000L, bufferedPositionMs = 0L, speed = PlaybackSpeed.S1_0,
            loopStartMs = null, loopEndMs = null, continuousLoop = false, rotationDeg = 0,
        )

        override fun load(
            uri: Uri,
            initialPositionMs: Long,
            speed: PlaybackSpeed,
            audioOffsetMs: Int,
            loopStartMs: Long?,
            loopEndMs: Long?,
            continuousLoop: Boolean,
        ) {
            loadCalls += LoadCall(initialPositionMs, speed, audioOffsetMs, loopStartMs, loopEndMs, continuousLoop)
        }

        override fun play() {
            playCalls++
        }

        override fun pause() {
            pauseCalls++
        }

        override fun seekTo(positionMs: Long, exact: Boolean) = run { seekCalls += positionMs to exact }
        override fun setSpeed(speed: PlaybackSpeed) = run { speedCalls += speed }
        override fun setSelection(startMs: Long, endMs: Long) = run { selectionCalls += startMs to endMs }
        override fun clearSelection() {
            clearSelectionCalls++
        }

        override fun enableContinuousLoop(enabled: Boolean) = run { continuousLoopCalls += enabled }
        override fun startCountdown(fromMs: Long, leadMs: Long) = run { countdownCalls += fromMs to leadMs }
        override fun cancelCountdown() {
            cancelCountdownCalls++
        }

        override fun setAudioOffsetMs(offsetMs: Int): Int = run {
            audioOffsetCalls += offsetMs
            offsetMs.coerceIn(-200, 200) // 引擎 ±200 钳制：负值 = 视频延迟生效
        }

        override fun setVideoSurface(surface: android.view.Surface?) {
            videoSurfaceCalls += surface
        }

        override suspend fun captureFrameAt(positionMs: Long, maxWidthPx: Int): Bitmap? = captureResult
        override fun setVideoOrientationListener(listener: ((Int) -> Unit)?) = Unit
        override fun getSnapshot(): PlayerSnapshot = snapshotValue
        override fun release() = run { released = true }
    }

    private class FakeVideoRepository : VideoRepository {
        var readable = true
        var metadata = VideoMetadata(durationMs = 90_000L, frameRate = 30f, rotationDeg = 0)
        val permissionUris = mutableListOf<Uri>()
        val readMetadataUris = mutableListOf<Uri>()
        val readabilityChecks = mutableListOf<Uri>()

        override fun takePersistablePermission(uri: Uri) {
            permissionUris += uri
        }

        override suspend fun readMetadata(uri: Uri): VideoMetadata {
            readMetadataUris += uri
            return metadata
        }

        override suspend fun isUriReadable(uri: Uri): Boolean {
            readabilityChecks += uri
            return readable
        }

        override fun resolveDocId(uri: Uri): String? = null
    }

    private class FakePreferenceRepository : PreferenceRepository {
        override val settings = MutableStateFlow(AppSettings())
        override val onboarding = MutableStateFlow(OnboardingStatus())
        var history: HistoryEntry? = null
        val savedHistories = mutableListOf<HistoryEntry>()
        val removedHistories = mutableListOf<String>()
        val countdownSecSet = mutableListOf<Int>()
        val defaultSpeeds = mutableListOf<PlaybackSpeed>()
        val introDoneSet = mutableListOf<Boolean>()
        val playerTutorialDoneSet = mutableListOf<Boolean>()
        val moreMenuTutorialDoneSet = mutableListOf<Boolean>()
        var onboardingResetCount = 0

        override suspend fun saveHistory(entry: HistoryEntry) {
            savedHistories += entry
        }

        override suspend fun loadHistory(uri: String): HistoryEntry? = history
        override suspend fun removeHistory(uri: String) {
            removedHistories += uri
        }

        override suspend fun setDefaultSpeed(speed: PlaybackSpeed) {
            defaultSpeeds += speed
        }

        override suspend fun setCountdownSec(sec: Int) {
            countdownSecSet += sec
        }

        override suspend fun setIntroDone(done: Boolean) {
            introDoneSet += done
        }

        override suspend fun setPlayerTutorialDone(done: Boolean) {
            playerTutorialDoneSet += done
        }

        override suspend fun setMoreMenuTutorialDone(done: Boolean) {
            moreMenuTutorialDoneSet += done
        }

        override suspend fun resetOnboarding() {
            onboardingResetCount++
        }
    }

    private class FakeVideoLibraryRepository : VideoLibraryRepository {
        val items = MutableStateFlow<List<LibraryItem>>(emptyList())
        val deletedUris = mutableListOf<String>()
        val lastOpened = mutableListOf<Pair<String, Long>>()
        val notes = mutableListOf<Pair<String, String?>>()
        val addCalls = mutableListOf<Triple<String, String, String>>()

        override fun listFlow(): Flow<List<LibraryItem>> = items
        override suspend fun list(): List<LibraryItem> = items.value
        override suspend fun getItem(uri: String): LibraryItem? = items.value.firstOrNull { it.uri == uri }

        override suspend fun add(uri: String, displayName: String, docId: String): Boolean {
            addCalls += Triple(uri, displayName, docId)
            return true
        }

        override suspend fun updateNote(uri: String, note: String?) {
            notes += uri to note
        }

        override suspend fun updateLastOpened(uri: String, at: Long) {
            lastOpened += uri to at
        }

        override suspend fun updateThumb(uri: String, thumbPath: String) = Unit
        override suspend fun deleteVideo(uri: String) {
            deletedUris += uri
        }
    }

    private class FakeBookmarkRepository : BookmarkRepository {
        val marks = MutableStateFlow<List<FrameMark>>(emptyList())
        val added = mutableListOf<Pair<String, FrameMark>>()
        val updated = mutableListOf<Pair<String, FrameMark>>()
        val removed = mutableListOf<Pair<String, String>>()
        val removedAllUris = mutableListOf<String>()

        override fun listFlow(uri: String): Flow<List<FrameMark>> = marks
        override suspend fun list(uri: String): List<FrameMark> = marks.value

        override suspend fun add(uri: String, mark: FrameMark) {
            added += uri to mark
        }

        override suspend fun update(uri: String, mark: FrameMark) {
            updated += uri to mark
        }

        override suspend fun remove(uri: String, markId: String) {
            removed += uri to markId
        }

        override suspend fun removeAll(uri: String) {
            removedAllUris += uri
        }
    }
}