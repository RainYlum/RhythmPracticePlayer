package com.rythm.app.player

/**
 * 单次区间练习监控（详细设计 4.3 / 架构 6.2，I2 主流程）：
 * 选区开启且非连续循环时，引擎位置 ≥ B' → 回调 onHit（暂停 + 复位 A'），不重建源。
 *
 * 语义：
 * - hasFired 在位置回落（< B'）后复位，允许下一圈命中；
 * - 触发后 [debounceMs] 去抖，防 seek 生效前的残余 tick 重复触发；
 * - 连续循环模式由本监控器忽略（引擎 LoopingMediaSource 自带回绕，P0-1）。
 */
class SelectionMonitor(
    private val onHit: (resetToMs: Long) -> Unit,
    private val debounceMs: Long = DEBOUNCE_MS,
) {

    private var hasFired = false
    private var lastHitAtMs: Long? = null

    /**
     * @param nowMs 注入时钟（单测可伪造；生产传 SystemClock.elapsedRealtime）。
     */
    fun onPositionTick(
        enginePosMs: Long,
        selectionActive: Boolean,
        continuousLoop: Boolean,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
    ) {
        if (!selectionActive || continuousLoop) {
            hasFired = false
            return
        }
        if (enginePosMs < endMs) {
            hasFired = false
            return
        }
        val last = lastHitAtMs
        if (!hasFired && (last == null || nowMs - last >= debounceMs)) {
            hasFired = true
            lastHitAtMs = nowMs
            onHit(startMs)
        }
    }

    /** 选区/源切换时清零。 */
    fun reset() {
        hasFired = false
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
    }
}