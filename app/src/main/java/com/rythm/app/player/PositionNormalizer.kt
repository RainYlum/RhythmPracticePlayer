package com.rythm.app.player

/**
 * 连续循环态位置归一化（架构 P0-1）：LoopingMediaSource 的 timeline 由 10000 个 period 组成，
 * getCurrentPosition() 为跨圈累计值，必须换算回圈内真实偏移。
 * 幂等纯函数：displayPos = A' + (enginePos − A') % (B' − A')。
 * 单次区间模式引擎 timeline 即原片，无需归一化（直读即可，I2）。
 */
object PositionNormalizer {

    fun normalize(enginePosMs: Long, loopStartMs: Long, loopEndMs: Long): Long {
        val len = loopEndMs - loopStartMs
        if (len <= 0L) return loopStartMs
        val offset = (enginePosMs - loopStartMs).mod(len)
        return loopStartMs + offset
    }
}