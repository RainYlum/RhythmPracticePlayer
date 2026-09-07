package com.rythm.app.util

/** 时间文本格式化（FR-02.4：毫秒级时间显示开关，谱面确认用）。 */
object TimeFormatter {

    /** mm:ss（默认显示）。超过 1 小时时分钟继续累计。 */
    fun format(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }

    /** mm:ss.mmm（毫秒档，1ms 精度，AC-02.5）。 */
    fun formatMillis(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val m = safe / 60_000
        val s = (safe % 60_000) / 1000
        val mmm = safe % 1000
        return "%02d:%02d.%03d".format(m, s, mmm)
    }

    /** 选区对齐值短格式：+12.345s → "12.345s"。 */
    fun formatSeconds(ms: Long): String = "%.3fs".format(ms.coerceAtLeast(0L) / 1000.0)
}