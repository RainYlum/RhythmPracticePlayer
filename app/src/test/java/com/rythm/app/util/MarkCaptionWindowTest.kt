package com.rythm.app.util

import com.rythm.app.model.FrameMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkCaptionWindowTest {

    private fun mark(id: String, timeMs: Long, text: String) =
        FrameMark(id = id, timeMs = timeMs, text = text)

    private val marks = listOf(
        mark("a", 10_000L, "换指法"),
        mark("b", 20_000L, ""),       // 空文本 → 不提醒
        mark("c", 30_000L, "疑似漏判"),
    )

    @Test
    fun `窗口前后沿 T-3000`() {
        // T−3001 无；T−3000 有
        assertTrue(MarkCaptionWindow.activeCaptions(10_000L - 3_001L, marks).isEmpty())
        assertEquals(listOf("换指法"), MarkCaptionWindow.activeCaptions(10_000L - 3_000L, marks))
    }

    @Test
    fun `窗口前后沿 T+3000`() {
        // T+3000 仍有；T+3001 无
        assertEquals(listOf("换指法"), MarkCaptionWindow.activeCaptions(10_000L + 3_000L, marks))
        assertTrue(MarkCaptionWindow.activeCaptions(10_000L + 3_001L, marks).isEmpty())
    }

    @Test
    fun `空文本不提醒`() {
        val result = MarkCaptionWindow.activeCaptions(20_000L, marks) // 恰在空文本标记窗口内
        assertTrue(result.isEmpty())
    }

    @Test
    fun `窗口重叠时按时间排序`() {
        // 12s：换指法（7~13s）与疑似漏判（27~33s）不重叠；用 28s 只出现 c
        assertEquals(listOf("疑似漏判"), MarkCaptionWindow.activeCaptions(28_000L, marks))
        // 构造两个重叠标记验证排序与多字幕
        val overlapped = listOf(
            mark("a", 10_000L, "标记A"),
            mark("b", 10_500L, "标记B"),
        )
        assertEquals(listOf("标记A", "标记B"), MarkCaptionWindow.activeCaptions(11_000L, overlapped))
    }

    @Test
    fun `最多 3 条超出省略`() {
        val many = listOf(
            mark("1", 10_000L, "一"),
            mark("2", 10_100L, "二"),
            mark("3", 10_200L, "三"),
            mark("4", 10_300L, "四"),
        )
        val result = MarkCaptionWindow.activeCaptions(10_500L, many)
        assertEquals(3, result.size)
        assertEquals(listOf("一", "二", "三"), result)
    }
}