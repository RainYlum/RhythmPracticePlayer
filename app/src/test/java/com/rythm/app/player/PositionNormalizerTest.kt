package com.rythm.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionNormalizerTest {

    @Test
    fun `直接映射第一圈`() {
        assertEquals(1_000L, PositionNormalizer.normalize(1_000L, 1_000L, 5_000L))
        assertEquals(4_999L, PositionNormalizer.normalize(4_999L, 1_000L, 5_000L))
    }

    @Test
    fun `跨圈累计值换算回圈内偏移`() {
        // 区间 1000~5000（长 4000），第 2 圈偏移 500 → 引擎 5500 → 归一 1500
        assertEquals(1_500L, PositionNormalizer.normalize(5_500L, 1_000L, 5_000L))
        // 第 100 圈偏移 3999 → 3999
        assertEquals(3_999L, PositionNormalizer.normalize(99L * 4_000L + 3_999L, 1_000L, 5_000L))
    }

    @Test
    fun `引擎位置恰为 B 时归一为 A`() {
        assertEquals(1_000L, PositionNormalizer.normalize(5_000L, 1_000L, 5_000L))
    }

    @Test
    fun `非法区间返回起点`() {
        assertEquals(1_000L, PositionNormalizer.normalize(9_999L, 1_000L, 1_000L))
        assertEquals(1_000L, PositionNormalizer.normalize(9_999L, 1_000L, 500L))
    }
}