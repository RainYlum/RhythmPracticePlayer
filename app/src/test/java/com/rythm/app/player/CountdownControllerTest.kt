package com.rythm.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CountdownController 时序验证（注入 fake 时钟）：预位→回退→起播顺序、取消复位、watchdog 降级。
 */
class CountdownControllerTest {

    private val seeks = mutableListOf<Long>()
    private var position = 0L
    private var plays = 0
    private var pauses = 0
    private var finished: Long? = null
    private var fallbackMessage: String? = null
    private var now = 0L

    private fun controller(): CountdownController = CountdownController(
        seekTo = { pos, _ -> seeks.add(pos); position = pos },
        doPlay = { plays++ },
        doPause = { pauses++ },
        currentPosition = { position },
        onFinished = { finished = it },
        onFallback = { fallbackMessage = it },
        clock = { now },
    )

    @Test
    fun `start 顺序为先预位 S 再回退 3s 再起播`() {
        now = 100_000L
        position = 8_000L
        val c = controller()
        c.start(fromMs = 12_000L)
        assertEquals(listOf(12_000L, 9_000L), seeks) // ①seek S ②回退 S−3000（位置 8000 距 12000 超容差）
        assertEquals(1, plays)
        assertTrue(c.isActive)
    }

    @Test
    fun `当前位置在 S 附近时跳过预位 seek`() {
        now = 100_000L
        position = 11_900L
        val c = controller()
        c.start(fromMs = 12_000L)
        assertEquals(listOf(9_000L), seeks) // 只回退，不预位
        assertEquals(1, plays)
    }

    @Test
    fun `到达 S 容差内触发完成`() {
        now = 100_000L
        position = 0L
        val c = controller()
        c.start(fromMs = 10_000L) // seeks: 10_000 → 7_000, position=7000
        position = 9_800L // ≥ 10000−300
        c.onPositionTick(position)
        assertEquals(10_000L, finished)
        assertFalse(c.isActive)
    }

    @Test
    fun `watchdog 超时降级正常播放`() {
        now = 100_000L
        position = 0L
        val c = controller()
        c.start(fromMs = 10_000L) // position=7000 卡死不再前进
        now = 105_001L // 超 5000ms
        c.onPositionTick(position)
        assertEquals(10_000L, finished)
        assertEquals("倒计时同步异常，已转为正常播放", fallbackMessage)
        assertFalse(c.isActive)
    }

    @Test
    fun `watchdog 未超时且未到达不触发`() {
        now = 100_000L
        position = 0L
        val c = controller()
        c.start(fromMs = 10_000L)
        position = 7_100L
        now = 100_500L
        c.onPositionTick(position)
        assertEquals(null, finished)
        assertEquals(null, fallbackMessage)
        assertTrue(c.isActive)
    }

    @Test
    fun `cancel 暂停并精确复位 S`() {
        now = 100_000L
        position = 0L
        val c = controller()
        c.start(fromMs = 10_000L) // isActive=true
        c.cancel()
        assertEquals(1, pauses)
        assertEquals(10_000L, position) // 复位到 S
        assertFalse(c.isActive)
    }

    @Test
    fun `取消后再次 start 可复用`() {
        now = 100_000L
        position = 0L
        val c = controller()
        c.start(fromMs = 10_000L)
        c.cancel()
        position = 10_000L
        val completed = mutableListOf<Long>()
        val c2 = CountdownController(
            seekTo = { _, _ -> },
            doPlay = {},
            doPause = {},
            currentPosition = { 10_000L },
            onFinished = { completed.add(it) },
            onFallback = {},
            clock = { now },
        )
        c2.start(fromMs = 20_000L) // 位置在 S 附近（10000 距 20000 超容差→预位 20000）
        position = 20_000L
        c2.onPositionTick(position)
        assertEquals(listOf(20_000L), completed)
    }

    @Test
    fun `remainingMs 随位置递减`() {
        now = 100_000L
        position = 0L
        val c = controller()
        c.start(fromMs = 10_000L) // position=7000
        assertEquals(3_000, c.remainingMs())
        position = 9_000L
        assertEquals(1_000, c.remainingMs())
        position = 12_000L
        assertEquals(0, c.remainingMs()) // clamp
        c.cancel()
        assertEquals(null, c.remainingMs())
    }
}