package com.rythm.app.player

/**
 * 倒计时预听控制器（FR-10 路径 A：UI 静止帧遮罩 + 本类只负责音频先行时序）：
 *
 * start(S)：
 *  ① 若当前不在 S 附近 → seekTo(S) 预位（exact）；
 *  ② seekTo(max(0, S − 3000)) 回退；
 *  ③ play() —— 音频按当前倍速先行，画面被 UI 遮罩定格。
 *
 * 完成判定：引擎位置 ≥ S − 容差（随 250ms 轮询）→ onFinished(S)；
 * 兜底：play 后 WATCHDOG_MS 内未达 S（seek 卡死/引擎异常）→ onFallback 降级正常播放（R-03c）。
 * cancel()：pause + seekTo(S) 精确复位（I8 起点语义），由 UI 取消按钮触发。
 *
 * 全部位置均为归一化时间轴值（I5）。控制器闭包与时钟均注入，便于单测。
 */
class CountdownController(
    private val seekTo: (positionMs: Long, exact: Boolean) -> Unit,
    private val doPlay: () -> Unit,
    private val doPause: () -> Unit,
    private val currentPosition: () -> Long,
    private val onFinished: (fromMs: Long) -> Unit,
    private val onFallback: (message: String) -> Unit,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    private var activeFromMs: Long? = null
    private var startedAtMs = 0L

    val isActive: Boolean
        get() = activeFromMs != null

    fun start(fromMs: Long) {
        if (activeFromMs != null) return
        // ① 预位 S（避免从远处直接回退造成起点漂移）
        if (kotlin.math.abs(currentPosition() - fromMs) > ARRIVE_TOLERANCE_MS) {
            seekTo(fromMs, true)
        }
        // ② 回退前导窗口，音频先行
        seekTo((fromMs - LEAD_WINDOW_MS).coerceAtLeast(0L), true)
        activeFromMs = fromMs
        startedAtMs = clock()
        // ③ 起播
        doPlay()
    }

    fun cancel() {
        val fromMs = activeFromMs ?: return
        activeFromMs = null
        doPause()
        seekTo(fromMs, true)
    }

    /** 引擎位置心跳（250ms 轮询通道调用）。 */
    fun onPositionTick(positionMs: Long) {
        val fromMs = activeFromMs ?: return
        if (positionMs >= fromMs - ARRIVE_TOLERANCE_MS) {
            finish(fromMs)
        } else if (clock() - startedAtMs > WATCHDOG_MS) {
            // seek 卡死 / 引擎异常 → 降级正常播放并提示（R-03c 兜底）
            onFallback("倒计时同步异常，已转为正常播放")
            finish(fromMs)
        }
    }

    /** 倒计时剩余（供 UI 外推基准，负值 clamp 为 0）。 */
    fun remainingMs(): Int? {
        val fromMs = activeFromMs ?: return null
        return (fromMs - currentPosition()).toInt().coerceIn(0, Int.MAX_VALUE)
    }

    private fun finish(fromMs: Long) {
        activeFromMs = null
        onFinished(fromMs)
    }

    companion object {
        /** 前导静听窗口：seek 回退毫秒数（FR-10 音频先行）。 */
        const val LEAD_WINDOW_MS = 3000L

        /** 到达判定容差（覆盖 250ms 轮询周期 + 引擎 seek 回读延迟）。 */
        const val ARRIVE_TOLERANCE_MS = 300L

        /** 起播后最大等待，超时降级正常播放。 */
        const val WATCHDOG_MS = 5000L
    }
}