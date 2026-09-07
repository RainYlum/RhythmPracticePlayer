package com.rythm.app.ui.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.rythm.app.model.PlaybackSpeed
import com.rythm.app.model.PlayerPhase
import com.rythm.app.model.PlaybackUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Slider 等连续输入组件语义（ProgressBarRangeInfo）判定，I4 无滑块断言用。 */
internal val sliderSemantics = SemanticsMatcher("slider") { node ->
    node.config.contains(SemanticsProperties.ProgressBarRangeInfo)
}

/**
 * 播放控制面板 Compose 冒烟（I4 断言）：默认仅进度/播放控制两行，点「更多功能」后
 * 展开快捷行/倍速/倒计时/音频延迟等功能；倍速合并按钮（默认 1.0x，testTag=speedButton）
 * 点击后向上弹出六档菜单、当前档选中高亮、无滑块/连续输入组件；
 * 音频延迟为 ±5ms 步进（±200ms 上限）。
 */
class PlayerControlsSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    private val controlState = PlaybackUiState(
        durationMs = 90_000L,
        phase = PlayerPhase.READY,
        playbackSpeed = PlaybackSpeed.S0_6,
    )

    private fun setPanel(
        state: PlaybackUiState,
        expanded: Boolean = false,
        onSpeed: (PlaybackSpeed) -> Unit = {},
        onAudioOffset: (Int) -> Unit = {},
        onExpandMore: () -> Unit = {},
    ) {
        rule.setContent {
            MaterialTheme {
                PlayerControlsPanel(
                    state = state,
                    nowMs = 0L,
                    marks = emptyList(),
                    panelExpanded = expanded,
                    onExpandMore = onExpandMore,
                    onPlay = {},
                    onPause = {},
                    onSeek = {},
                    onScrub = {},
                    onStepBack = {},
                    onStepFwd = {},
                    onSpeed = onSpeed,
                    onQuickLoop = {},
                    onClearSelection = {},
                    onNudgeA = {},
                    onNudgeB = {},
                    onNudgeDragA = {},
                    onNudgeDragB = {},
                    onToggleContinuousLoop = {},
                    onCountdownSec = {},
                    onAudioOffset = onAudioOffset,
                    onAddMark = {},
                    onOpenBookmarks = {},
                )
            }
        }
    }

    @Test
    fun 默认折叠仅进度与播放控制_点击更多功能展开后不隐藏入口() {
        rule.setContent {
            var expanded by remember { mutableStateOf(false) }
            MaterialTheme {
                PlayerControlsPanel(
                    state = controlState,
                    nowMs = 0L,
                    marks = emptyList(),
                    panelExpanded = expanded,
                    onExpandMore = { expanded = true },
                    onPlay = {},
                    onPause = {},
                    onSeek = {},
                    onScrub = {},
                    onStepBack = {},
                    onStepFwd = {},
                    onSpeed = {},
                    onQuickLoop = {},
                    onClearSelection = {},
                    onNudgeA = {},
                    onNudgeB = {},
                    onNudgeDragA = {},
                    onNudgeDragB = {},
                    onToggleContinuousLoop = {},
                    onCountdownSec = {},
                    onAudioOffset = {},
                    onAddMark = {},
                    onOpenBookmarks = {},
                )
            }
        }

        // 折叠态：只有「更多功能」入口，无 AB/倒计时等其它功能
        rule.onNodeWithText("更多功能").assertExists()
        rule.onAllNodes(hasText("AB 设点")).assertCountEquals(0)
        rule.onAllNodes(hasText("倒计时")).assertCountEquals(0)
        rule.onAllNodes(hasText("连续循环")).assertCountEquals(0)

        // 点击展开：其它功能出现，「更多功能」入口消失（本次练习内不再隐藏）
        rule.onNodeWithText("更多功能").performClick()
        rule.onNodeWithText("AB 设点").assertExists()
        rule.onNodeWithText("倒计时 3s").assertExists()
        rule.onNodeWithText("连续循环").assertExists()
        rule.onAllNodes(hasText("更多功能")).assertCountEquals(0)
    }

    @Test
    fun 倍速按钮显示当前档且菜单六档齐全无滑块() {
        setPanel(controlState, expanded = true)

        // 合并按钮显示当前档（0.6x）；默认档 1.0x 仅在设置默认时出现
        rule.onNodeWithTag("speedButton").assertExists()
        rule.onNodeWithText("0.6x ▾").assertExists()

        // 点击按钮向上弹出菜单：六档全部渲染（FR-03.1，I4），无任何连续输入组件
        rule.onNodeWithTag("speedButton").performClick()
        PlaybackSpeed.entries.forEach { speed ->
            rule.onNodeWithText(speed.label).assertExists()
        }
        rule.onAllNodes(sliderSemantics).assertCountEquals(0)
    }

    @Test
    fun 点击倍速菜单项回调对应档位() {
        var selected: PlaybackSpeed? = null
        setPanel(controlState, expanded = true, onSpeed = { selected = it })

        rule.onNodeWithTag("speedButton").performClick()
        rule.onNodeWithText("0.7x").performClick()
        assertEquals(PlaybackSpeed.S0_7, selected)

        rule.onNodeWithTag("speedButton").performClick()
        rule.onNodeWithText("0.5x").performClick()
        assertEquals(PlaybackSpeed.S0_5, selected)

        rule.onNodeWithTag("speedButton").performClick()
        rule.onNodeWithText("1.0x").performClick()
        assertEquals(PlaybackSpeed.S1_0, selected)
    }

    @Test
    fun 非六档档位不存在() {
        setPanel(controlState, expanded = true)
        rule.onNodeWithTag("speedButton").performClick()

        // 0.25x / 1.5x / 2.0x 等滑块时代的连续档位不得出现（FR-03.1）
        rule.onAllNodes(hasText("0.25x")).assertCountEquals(0)
        rule.onAllNodes(hasText("1.5x")).assertCountEquals(0)
        rule.onAllNodes(hasText("2.0x")).assertCountEquals(0)
    }

    @Test
    fun 音频延迟正负5ms步进回调() {
        val offsets = mutableListOf<Int>()
        setPanel(controlState, expanded = true, onAudioOffset = { offsets += it })

        rule.onNodeWithText("+5ms").performClick()
        assertEquals(listOf(5), offsets)
        rule.onNodeWithText("−5ms").performClick()
        assertEquals(listOf(5, -5), offsets)
    }
}