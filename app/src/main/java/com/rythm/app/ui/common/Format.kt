package com.rythm.app.ui.common

import java.util.Locale

/** 播放时间文本（FR-02.4 默认毫秒精度时间戳）：mm:ss.mmm。 */
fun formatPlayTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val mm = safe / 60_000
    val ss = (safe % 60_000) / 1000
    return String.format(Locale.US, "%02d:%02d.%03d", mm, ss, safe % 1000)
}