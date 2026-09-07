package com.rythm.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionMonitorTest {

    private val hits = mutableListOf<Long>()
    // 单实例：跨调用状态（hasFired/lastHitAtMs）必须在同一实例上保持
    private val monitor = SelectionMonitor(onHit = { hits.add(it) }, debounceMs = 500L)

    @Test
    fun `到达 B 点触发一次命中并复位到 A`() {
        monitor.onPositionTick(
            enginePosMs = 10_000L,
            selectionActive = true,
            continuousLoop = false,
            startMs = 3_000L,
            endMs = 10_000L,
            nowMs = 1_000L,
        )
        assertEquals(listOf(3_000L), hits)
    }

    @Test
    fun `位置回落 B 前复位后可再次命中`() {
        monitor.onPositionTick(enginePosMs = 10_000L, selectionActive = true, continuousLoop = false, startMs = 0L, endMs = 10_000L, nowMs = 1_000L)
        assertEquals(1, hits.size)

        // 复位后位置回落到区间内
        monitor.onPositionTick(enginePosMs = 4_000L, selectionActive = true, continuousLoop = false, startMs = 0L, endMs = 10_000L, nowMs = 2_000L)
        // 再次越过 B → 第二圈命中
        monitor.onPositionTick(enginePosMs = 10_001L, selectionActive = true, continuousLoop = false, startMs = 0L, endMs = 10_000L, nowMs = 3_000L)
        assertEquals(2, hits.size)
    }

    @Test
    fun `去抖窗口内不重复触发`() {
        monitor.onPositionTick(enginePosMs = 100_500L, selectionActive = true, continuousLoop = false, startMs = 0L, endMs = 100_000L, nowMs = 1_000L)
        // 仍未复位（hasFired=true），且 500ms 去抖内
        monitor.onPositionTick(enginePosMs = 100_900L, selectionActive = true, continuousLoop = false, startMs = 0L, endMs = 100_000L, nowMs = 1_400L)
        assertEquals(1, hits.size)
    }

    @Test
    fun `已触发未复位时不会再次命中`() {
        monitor.onPositionTick(enginePosMs = 100_500L, selectionActive = true, continuousLoop = false, startMs = 0L, endMs = 100_000L, nowMs = 1_000L)
        // hasFired=true 且位置仍 ≥ B'（seek 未生效的残余 tick）
        monitor.onPositionTick(enginePosMs = 100_900L, selectionActive = true, continuousLoop = false, startMs = 0L, endMs = 100_000L, nowMs = 2_000L)
        assertEquals(1, hits.size)
    }

    @Test
    fun `连续循环模式忽略`() {
        monitor.onPositionTick(enginePosMs = 9_999_999L, selectionActive = true, continuousLoop = true, startMs = 0L, endMs = 10_000L, nowMs = 1_000L)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `选区关闭时忽略并复位`() {
        monitor.onPositionTick(enginePosMs = 100_500L, selectionActive = true, continuousLoop = false, startMs = 0L, endMs = 100_000L, nowMs = 1_000L)
        assertEquals(1, hits.size)
        monitor.onPositionTick(enginePosMs = 100_500L, selectionActive = false, continuousLoop = false, startMs = 0L, endMs = 100_000L, nowMs = 2_000L)
        // 关闭选区不会触发
        assertEquals(1, hits.size)
    }

    @Test
    fun `reset 清零触发标志`() {
        monitor.onPositionTick(enginePosMs = 100_500L, selectionActive = true, continuousLoop = false, startMs = 0L, endMs = 100_000L, nowMs = 1_000L)
        monitor.reset()
        monitor.onPositionTick(enginePosMs = 100_501L, selectionActive = true, continuousLoop = false, startMs = 0L, endMs = 100_000L, nowMs = 2_000L)
        assertEquals(2, hits.size)
    }
}