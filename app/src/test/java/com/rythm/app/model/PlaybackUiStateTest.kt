package com.rythm.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PlaybackUiState UI 态派生与外推规则（架构 6.3.1 / 详设 3.4.2）。
 */
class PlaybackUiStateTest {

    private fun state(phase: PlayerPhase, playing: Boolean = false) =
        PlaybackUiState(phase = phase, isPlaying = playing)

    @Test
    fun `controlVisible 相位矩阵`() {
        assertFalse(state(PlayerPhase.IDLE).controlVisible)
        assertFalse(state(PlayerPhase.LOADING).controlVisible)
        assertTrue(state(PlayerPhase.READY, playing = false).controlVisible) // 暂停控制窗
        assertFalse(state(PlayerPhase.READY, playing = true).controlVisible) // 沉浸
        assertTrue(state(PlayerPhase.COUNTDOWN).controlVisible) // 倒计时取消按钮
        assertTrue(state(PlayerPhase.COUNTDOWN, playing = true).controlVisible)
        assertFalse(state(PlayerPhase.BUFFERING).controlVisible)
        assertFalse(state(PlayerPhase.BUFFERING, playing = true).controlVisible)
        assertTrue(state(PlayerPhase.ENDED).controlVisible) // FR-02.5 播完重播控制
        assertFalse(state(PlayerPhase.ERROR).controlVisible) // 错误横幅独立呈现
    }

    @Test
    fun `immersive 与 isCountdown 派生`() {
        // 播放中且非倒计时 = 沉浸
        val playing = state(PlayerPhase.READY, playing = true)
        assertTrue(playing.isPlaying)
        assertFalse(playing.isCountdown)
        assertTrue(playing.immersive)
        // 倒计时中即使播放也不算沉浸
        val countdown = state(PlayerPhase.COUNTDOWN, playing = true)
        assertTrue(countdown.isCountdown)
        assertFalse(countdown.immersive)
        // 暂停
        assertFalse(state(PlayerPhase.READY, playing = false).immersive)
    }

    @Test
    fun `外推位置随速度斜率`() {
        val s = PlaybackUiState(
            phase = PlayerPhase.READY,
            isPlaying = true,
            displayPositionMs = 10_000,
            tickBasePositionMs = 10_000,
            tickBaseElapsedMs = 0,
            tickBaseSpeed = PlaybackSpeed.S1_0,
        )
        assertEquals(10_500L, s.extrapolatedDisplayPositionMs(500))

        val slow = s.copy(tickBaseSpeed = PlaybackSpeed.S0_5)
        assertEquals(10_250L, slow.extrapolatedDisplayPositionMs(500))

        assertEquals(10_000L, slow.extrapolatedDisplayPositionMs(-1_000)) // 负外推钳制为 0
    }

    @Test
    fun `非沉浸时外推返回引擎确认值`() {
        val paused = PlaybackUiState(
            phase = PlayerPhase.READY,
            isPlaying = false,
            displayPositionMs = 12_345,
            tickBasePositionMs = 12_000,
            tickBaseElapsedMs = 0,
            tickBaseSpeed = PlaybackSpeed.S1_0,
        )
        assertEquals(12_345L, paused.extrapolatedDisplayPositionMs(5_000))

        val ended = paused.copy(phase = PlayerPhase.ENDED)
        assertEquals(12_345L, ended.extrapolatedDisplayPositionMs(5_000))
    }
}