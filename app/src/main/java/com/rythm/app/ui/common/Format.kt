package com.rythm.app.ui.common

import java.util.Locale

/** 播放时间文本（FR-02.4 毫秒精度开关）：mm:ss 或 mm:ss.mmm。 */
fun formatPlayTime(ms: Long, showMillis: Boolean = false): String {
    val safe = ms.coerceAtLeast(0L)
    val mm = safe / 60_000
    val ss = (safe % 60_000) / 1000
    return if (showMillis) {
        String.format(Locale.US, "%02d:%02d.%03d", mm, ss, safe % 1000)
    } else {
        String.format(Locale.US, "%02d:%02d", mm, ss)
    }
}