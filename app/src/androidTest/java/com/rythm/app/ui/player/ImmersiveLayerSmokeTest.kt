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
 * 无任何控制组件；单击不退出（轻提示），300ms 窗口内双击退出（双击窗口由
 * System.currentTimeMillis 判定，测试内两次 performClick 真实间隔在毫秒级）。
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
                onExitImmersive = {},
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
    }

    @Test
    fun 单击退出按钮不退出_双击退出() {
        var exits = 0
        rule.setContent {
            ImmersiveLayer(
                captions = emptyList(),
                buffering = false,
                onExitImmersive = { exits++ },
            )
        }

        val exit = rule.onNodeWithContentDescription("退出沉浸")
        exit.performClick() // 第一击：仅提示，不退出（FR-02.7）
        assertEquals(0, exits)

        exit.performClick() // 300ms 窗口内第二击（真实间隔毫秒级）
        rule.waitForIdle()
        assertEquals(1, exits)
    }
}