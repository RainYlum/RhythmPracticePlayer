package com.rythm.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {

    @Test
    fun `from 取最近档位`() {
        assertEquals(PlaybackSpeed.S0_5, PlaybackSpeed.from(0.5f))
        assertEquals(PlaybackSpeed.S0_6, PlaybackSpeed.from(0.56f))
        assertEquals(PlaybackSpeed.S0_7, PlaybackSpeed.from(0.7f))
        // 0.75f 为 0.7/0.8 双等距中点（float 对称），minBy 取前档
        assertEquals(PlaybackSpeed.S0_7, PlaybackSpeed.from(0.75f))
        assertEquals(PlaybackSpeed.S0_8, PlaybackSpeed.from(0.76f))
        assertEquals(PlaybackSpeed.S0_9, PlaybackSpeed.from(0.9f))
        assertEquals(PlaybackSpeed.S0_9, PlaybackSpeed.from(0.95f)) // 0.95f 为 0.9/1.0 双等距中点（float 对称），取前档
        assertEquals(PlaybackSpeed.S1_0, PlaybackSpeed.from(1.0f))
        // 0.55f 为 0.5/0.6 双等距中点（float 对称），取前档
        assertEquals(PlaybackSpeed.S0_5, PlaybackSpeed.from(0.54f))
        assertEquals(PlaybackSpeed.S0_5, PlaybackSpeed.from(0.55f))
    }

    @Test
    fun `边界值取整`() {
        // 低于最低档/高于最高档时收敛到边界档
        assertEquals(PlaybackSpeed.S0_5, PlaybackSpeed.from(0.1f))
        assertEquals(PlaybackSpeed.S1_0, PlaybackSpeed.from(2.0f))
    }

    @Test
    fun `默认档为 1_0`() {
        assertEquals(PlaybackSpeed.S1_0, PlaybackSpeed.DEFAULT)
    }
}