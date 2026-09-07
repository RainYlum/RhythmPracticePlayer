package com.rythm.app.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStepPolicyTest {

    private val policy = FrameStepPolicy(frameToleranceMs = 50L)

    @Test
    fun `0_1s 档步进目标基于实际落点`() {
        // 引擎实际落点 1000（用户基准 960，说明上次 seek 吸到关键帧）
        val decision = policy.planStep(
            StepRequest(baseMs = 960L, deltaMs = FrameStepPolicy.STEP_100MS),
            lastActualMs = 1_000L,
        )
        assertEquals(1_100L, decision.targetMs) // target = lastActual + delta
        assertTrue(decision.approximate) // |1000 − 1060| = 60 > 50 → 近似标记（详细设计 3.1.3）

        // 偏差在容差内不标记近似
        val ok = policy.planStep(
            StepRequest(baseMs = 1_050L, deltaMs = FrameStepPolicy.STEP_100MS),
            lastActualMs = 1_150L,
        )
        assertEquals(1_250L, ok.targetMs)
        assertFalse(ok.approximate)
    }

    @Test
    fun `0_1s 档落关键帧偏差超容差时标记近似`() {
        // 请求 1060，实际落 1000 → 偏差 60ms > 50ms
        val decision = policy.planStep(
            StepRequest(baseMs = 960L, deltaMs = FrameStepPolicy.STEP_100MS),
            lastActualMs = 1_000L,
        )
        assertTrue(decision.approximate)
    }

    @Test
    fun `1s 档不标记近似`() {
        // 1s 档即使落点偏差超容差也不提示（关键帧间隔相对 1s 步进可忽略）
        val decision = policy.planStep(
            StepRequest(baseMs = 960L, deltaMs = FrameStepPolicy.STEP_1S),
            lastActualMs = 1_000L,
        )
        assertEquals(2_000L, decision.targetMs)
        assertFalse(decision.approximate)
    }

    @Test
    fun `目标不为负`() {
        val decision = policy.planStep(
            StepRequest(baseMs = 50L, deltaMs = -FrameStepPolicy.STEP_100MS),
            lastActualMs = 50L,
        )
        assertEquals(0L, decision.targetMs)
    }
}