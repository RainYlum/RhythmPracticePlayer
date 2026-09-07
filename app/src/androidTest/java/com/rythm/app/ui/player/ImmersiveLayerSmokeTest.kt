package com.rythm.app.ui.player

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 沉浸层 Compose 冒烟（阶段 6，I3/FR-02.6/02.7）：仅退出按钮 + 字幕，
 * 无任何控制组件；两次手势退出的窗口判定由 PlayerScreen 提升持有（首次仅提示
 * 「再点一次退出」，窗口内第二次才退出），本层只负责提示文案渲染与手势回调。
 */
class ImmersiveLayerSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun 沉浸层仅退出按钮与字幕_无控制面板组件() {
        rule.setContent {
            ImmersiveLayer(
                captions = listOf("重音", "副歌"),
                buffering = false,
                showExitHint = false,
                onExitGesture = {},
            )
        }

        // 仅存的交互元素：退出按钮（FR-02.6）
        rule.onNodeWithContentDescription("退出沉浸").assertIsDisplayed()
        // 非交互字幕层（FR-06.6）
        rule.onNodeWithText("重音").assertIsDisplayed()
        rule.onNodeWithText("副歌").assertIsDisplayed()
        // 控制面板组件一律不得出现（I3）：倍速 chip / 倒计时配置 / 时间文本 / 滑块
        rule.onAllNodes(hasText("0.5x")).assertCountEquals(0)
        rule.onNodeWithText("倒计时").assertDoesNotExist()
        rule.onNodeWithText("毫秒").assertDoesNotExist()
        rule.onAllNodes(sliderSemantics).assertCountEquals(0)
        // 首次手势提示未触发时不渲染
        rule.onNodeWithText("再点一次退出").assertDoesNotExist()
    }

    @Test
    fun 首次手势仅提示_不退出_窗口内第二次手势才退出() {
        var gestures = 0
        rule.setContent {
            ImmersiveLayer(
                captions = emptyList(),
                buffering = false,
                showExitHint = true,
                onExitGesture = { gestures++ },
            )
        }

        // 提示（两次手势窗口内第一次）渲染「再点一次退出」
        rule.onNodeWithText("再点一次退出").assertIsDisplayed()

        // 退出按钮点击 = 手势回调；单独点击不退出（窗口判定在 PlayerScreen 层）
        val exit = rule.onNodeWithContentDescription("退出沉浸")
        exit.performClick()
        rule.waitForIdle()
        assertEquals(1, gestures)
        exit.performClick()
        rule.waitForIdle()
        assertEquals(2, gestures)
    }
}