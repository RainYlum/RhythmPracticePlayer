package com.rythm.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * AudioDelayProcessor 环形缓冲语义验证（纯 JVM，media3-common 无 Android 依赖）：
 * 延迟 = delayBytes 字节 = delayMs × sampleRate/1000 × bytesPerFrame，输出序列为输入序列的延迟复本。
 */
class AudioDelayProcessorTest {

    // 采样率 1000Hz、单声道 PCM16：1ms = 2 字节，便于手算（Java 构造器不支持命名参数，用位置参数）
    private val format = AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT)

    private fun proc(delayMs: Int): AudioDelayProcessor = AudioDelayProcessor().apply {
        setDelayMs(delayMs)
        configure(format)
    }

    private fun bytes(vararg values: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(values.size * 2)
        values.forEach { buf.putShort(it.toShort()) }
        buf.flip()
        return buf
    }

    private fun drain(p: AudioDelayProcessor): List<Short> {
        val out = mutableListOf<Short>()
        var b = p.getOutput()
        while (b !== AudioProcessor.EMPTY_BUFFER) {
            while (b.hasRemaining()) out.add(b.short)
            b = p.getOutput()
        }
        return out
    }

    @Test
    fun `延迟 10ms 时输出为输入延迟 10 样本`() {
        val p = proc(delayMs = 10) // 10ms = 10 样本 = 20 字节
        assertTrue(p.isActive)

        // 填充期（累计 10 字节 < 窗口 20 字节）不输出
        p.queueInput(bytes(1, 2, 3, 4, 5))
        assertFalse(drain(p).isNotEmpty())

        // 输入 6..15：前 5 样本补满窗口（不输出），后 5 样本触发交换，输出延迟复本 1..5
        p.queueInput(bytes(6, 7, 8, 9, 10, 11, 12, 13, 14, 15))
        val firstBatch = drain(p)
        assertEquals(listOf<Short>(1, 2, 3, 4, 5), firstBatch)

        // 继续输入：输出接续 6..10（延迟线恒定窗口）
        p.queueInput(bytes(16, 17, 18, 19, 20))
        assertEquals(listOf<Short>(6, 7, 8, 9, 10), drain(p))
    }

    @Test
    fun `延迟 0ms 不激活`() {
        val p = proc(delayMs = 0)
        assertFalse(p.isActive)
        p.queueInput(bytes(1, 2, 3))
        assertTrue(drain(p).isEmpty())
    }

    @Test
    fun `超上限 clamp 到 200ms`() {
        val p = AudioDelayProcessor()
        p.setDelayMs(500) // > MAX_DELAY_MS=200
        assertEquals(200, p.getDelayMs())
        p.configure(format)
        assertTrue(p.isActive)
    }

    @Test
    fun `configure 前设置延迟在 configure 后生效`() {
        // load 时 setAudioOffsetMs 先于播放 configure 调用（时序安全）
        val p = AudioDelayProcessor()
        p.setDelayMs(5)
        assertFalse(p.isActive) // 无 format 时延迟为 0
        p.configure(format)
        assertTrue(p.isActive)
        p.queueInput(bytes(1, 2, 3, 4, 5))
        assertTrue(drain(p).isEmpty()) // 5ms 填充期
        p.queueInput(bytes(6, 7, 8, 9, 10))
        assertEquals(listOf<Short>(1, 2, 3, 4, 5), drain(p))
    }

    @Test
    fun `动态调整延迟重建队列`() {
        val p = AudioDelayProcessor()
        p.setDelayMs(5)
        p.configure(format)
        p.queueInput(bytes(1, 2, 3, 4, 5))
        assertTrue(drain(p).isEmpty())

        p.setDelayMs(10) // 播放中调整（FR-08 即时生效）：旧队列丢弃，重新按新窗口填充
        p.queueInput(bytes(6, 7, 8, 9, 10, 11, 12, 13, 14, 15))
        // 新窗口 10 样本：本次输入恰好填满，仍无输出
        assertTrue(drain(p).isEmpty())

        // 填充完成后开始输出新窗口下的延迟复本
        p.queueInput(bytes(16, 17, 18, 19, 20))
        assertEquals(listOf<Short>(6, 7, 8, 9, 10), drain(p))
    }

    @Test
    fun `flush 清空队列`() {
        val p = proc(delayMs = 10)
        p.queueInput(bytes(1, 2, 3, 4, 5))
        p.flush()
        p.queueInput(bytes(11, 12, 13, 14, 15))
        assertTrue(drain(p).isEmpty()) // flush 后重新走填充期
    }
}