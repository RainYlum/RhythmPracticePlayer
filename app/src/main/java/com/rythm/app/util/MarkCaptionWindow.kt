package com.rythm.app.util

import com.rythm.app.model.FrameMark

/**
 * 帧标记沉浸字幕窗口判定（FR-06.6，v1.5）——纯函数，可脱离 Android 单测。
 * 规则：
 * - 仅 text.isNotBlank() 的标记参与提醒；
 * - 窗口 = [timeMs − 3000, timeMs + 3000]；
 * - 结果按 timeMs 升序，最多 [MAX_CAPTIONS] 条（超出省略，详细设计 5.7）。
 */
object MarkCaptionWindow {

    const val WINDOW_MS = 3_000L
    const val MAX_CAPTIONS = 3

    fun activeCaptions(nowMs: Long, marks: List<FrameMark>): List<String> =
        marks.asSequence()
            .filter { it.text.isNotBlank() }
            .filter { nowMs in (it.timeMs - WINDOW_MS)..(it.timeMs + WINDOW_MS) }
            .sortedBy { it.timeMs }
            .map { it.text }
            .take(MAX_CAPTIONS)
            .toList()
}