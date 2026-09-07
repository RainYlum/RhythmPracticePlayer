package com.rythm.app.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopPolicyTest {

    // 30fps → 帧时长 33ms；minInterval 100ms；最小区间 = max(100, 66) = 100ms
    private val policy = LoopPolicy(minIntervalMs = 100L, frameDurationMsProvider = { 33L })

    @Test
    fun `合法选区返回 Valid`() {
        val result = policy.validate(1_000L, 2_000L, durationMs = 10_000L)
        assertTrue(result is LoopValidation.Valid)
        result as LoopValidation.Valid
        assertEquals(1_000L, result.startMs)
        assertEquals(2_000L, result.endMs)
    }

    @Test
    fun `B 早于或等于 A 时 Invalid_B_BEFORE_A`() {
        assertEquals(
            LoopValidation.Reason.B_BEFORE_A,
            (policy.validate(2_000L, 1_000L, 10_000L) as LoopValidation.Invalid).reason,
        )
        assertEquals(
            LoopValidation.Reason.B_BEFORE_A,
            (policy.validate(1_000L, 1_000L, 10_000L) as LoopValidation.Invalid).reason,
        )
    }

    @Test
    fun `区间小于 100ms 时 Invalid_TOO_SHORT`() {
        // 99ms < 100ms
        assertEquals(
            LoopValidation.Reason.TOO_SHORT,
            (policy.validate(1_000L, 1_099L, 10_000L) as LoopValidation.Invalid).reason,
        )
        // 恰好 100ms 合法
        assertTrue(policy.validate(1_000L, 1_100L, 10_000L) is LoopValidation.Valid)
    }

    @Test
    fun `区间按 2 帧时长约束（帧率低时取 2 帧）`() {
        // 帧率 10fps → 帧时长 100ms → 最小区间 = max(100, 200) = 200ms
        val slowPolicy = LoopPolicy(minIntervalMs = 100L, frameDurationMsProvider = { 100L })
        assertEquals(
            LoopValidation.Reason.TOO_SHORT,
            (slowPolicy.validate(1_000L, 1_150L, 10_000L) as LoopValidation.Invalid).reason,
        )
        assertTrue(slowPolicy.validate(1_000L, 1_200L, 10_000L) is LoopValidation.Valid)
    }

    @Test
    fun `B 超出时长时 Invalid_TOO_SHORT`() {
        assertEquals(
            LoopValidation.Reason.TOO_SHORT,
            (policy.validate(1_000L, 11_000L, 10_000L) as LoopValidation.Invalid).reason,
        )
    }

    @Test
    fun `负值视为 MISSING_A`() {
        assertEquals(
            LoopValidation.Reason.MISSING_A,
            (policy.validate(-1L, 1_000L, 10_000L) as LoopValidation.Invalid).reason,
        )
    }

    @Test
    fun `帧时长换算`() {
        assertEquals(33L, LoopPolicy.frameDurationMs(30f))
        assertEquals(40L, LoopPolicy.frameDurationMs(25f))
        // 帧率不可得 → 30fps 近似
        assertEquals(33L, LoopPolicy.frameDurationMs(null))
        assertEquals(33L, LoopPolicy.frameDurationMs(0f))
        assertEquals(33L, LoopPolicy.frameDurationMs(-1f))
    }
}