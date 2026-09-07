package com.rythm.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * 音频延迟处理器（FR-08 正值实现）：PCM16 环形队列，输出比输入晚 delayMs。
 * 通过 `DefaultAudioSink.Builder.setAudioProcessors` 挂入管线，与 Sonic 变速处理器共存
 * （Sonic 处理变速，本处理器施加恒定延迟，顺序互不影响，R-13 spike 通过）。
 *
 * 延迟语义：队列容量 = delayMs×采样率×帧字节。填充期（队列未满）只入队不输出；
 * 填满后每出队 n 字节同时入队 n 字节，窗口大小恒定。队列尾随 drain 阶段丢尾可接受
 * （≤100ms，静音尾，官方 DelayAudioProcessor 同策略）。
 *
 * 调整即时生效：动态 setDelayMs 重建队列；播放中调整产生 ≤100ms 音频停顿（预期行为）。
 */
class AudioDelayProcessor : BaseAudioProcessor() {

    private var delayBytes = 0
    private var queue = ByteArray(0)
    private var head = 0
    private var size = 0

    /** 待交付缓冲（已 flip：position=0, limit=可读字节）；EMPTY_BUFFER = 无产出。 */
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER

    private var requestedDelayMs = 0

    /**
     * 最近一次 configure 的输入格式（自维护）。
     * 注意：不可读 BaseAudioProcessor.inputAudioFormat 字段——media3 1.10 的 configure
     * 只暂存 pending 格式，active 字段直到首次 flush(StreamMetadata) 才写入；
     * configure 后立即调整延迟会读到 NOT_SET 导致延迟丢失（isActive 恒 false）。
     */
    private var configuredFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET

    /**
     * 更新延迟（对外毫秒，自动 clamp 到 0~MAX_DELAY_MS）。
     * configure 前调用仅记录，随 configure 生效；configure 后调用即时重建队列。
     */
    fun setDelayMs(delayMs: Int) {
        requestedDelayMs = delayMs.coerceIn(0, MAX_DELAY_MS)
        if (configuredFormat !== AudioProcessor.AudioFormat.NOT_SET) {
            rebuildFor(configuredFormat)
        }
    }

    fun getDelayMs(): Int = requestedDelayMs

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // 以参数为准保存格式（active 字段此时仍为 NOT_SET），供后续 setDelayMs 即时重建。
        configuredFormat = inputAudioFormat
        rebuildFor(configuredFormat)
        return inputAudioFormat
    }

    override fun isActive(): Boolean = delayBytes > 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive() || !inputBuffer.hasRemaining()) return
        val out = ByteBuffer.allocateDirect(inputBuffer.remaining())
            .order(inputBuffer.order()) // 字节序跟随输入，保证输出与输入解释一致
        while (inputBuffer.hasRemaining() && delayBytes > 0) {
            if (size >= delayBytes) {
                // 窗口已满：出队 → 输出，同时回填等量输入（保持延迟恒定）
                val n = minOf(inputBuffer.remaining(), delayBytes, out.remaining())
                if (n <= 0) break
                drainTo(out, n)
                enqueue(inputBuffer, n)
            } else {
                enqueue(inputBuffer, minOf(inputBuffer.remaining(), delayBytes - size))
            }
        }
        out.flip()
        outputBuffer = out
    }

    override fun getOutput(): ByteBuffer {
        val ready = outputBuffer
        if (ready === EMPTY_BUFFER) return EMPTY_BUFFER
        outputBuffer = EMPTY_BUFFER
        return ready
    }

    override fun onQueueEndOfStream() {
        // 丢失队列尾部样本（最多 100ms）：置空队列，立即进入 ended 判定
        head = 0
        size = 0
    }

    override fun onFlush() {
        head = 0
        size = 0
        outputBuffer = EMPTY_BUFFER
    }

    override fun onReset() {
        onFlush()
        delayBytes = 0
        queue = ByteArray(0)
        configuredFormat = AudioProcessor.AudioFormat.NOT_SET
    }

    private fun rebuildFor(format: AudioProcessor.AudioFormat) {
        val bytes = if (requestedDelayMs == 0 || format === AudioProcessor.AudioFormat.NOT_SET) {
            0
        } else {
            requestedDelayMs * format.sampleRate / 1000 * format.bytesPerFrame
        }
        if (bytes != delayBytes) {
            delayBytes = bytes
            queue = ByteArray(bytes)
            head = 0
            size = 0
        }
    }

    /** 队头 n 字节 → out。 */
    private fun drainTo(out: ByteBuffer, n: Int) {
        val first = minOf(n, queue.size - head)
        out.put(queue, head, first)
        val second = n - first
        if (second > 0) out.put(queue, 0, second)
        head = (head + n) % queue.size
        size -= n
    }

    /** 输入 n 字节 → 队尾。 */
    private fun enqueue(inputBuffer: ByteBuffer, n: Int) {
        val tail = (head + size) % queue.size
        val first = minOf(n, queue.size - tail)
        inputBuffer.get(queue, tail, first)
        val second = n - first
        if (second > 0) inputBuffer.get(queue, 0, second)
        size += n
    }

    companion object {
        const val MAX_DELAY_MS = 100
    }
}

/**
 * 音频延迟应用策略（FR-08 / R-13）：
 * - 正值 +0~+100ms（音频延后）→ 环形缓冲生效，随配置即时重建；
 * - 负值 −1~−100ms（音频提前=视频延后）→ 首版降级：仅记录、返回 0 生效值，
 *   UI 提示"仅支持正值"（R-13 允许的降级路径，不影响播放）。
 */
class AudioDelayController(private val processor: AudioDelayProcessor) {

    var lastEffectiveMs: Int = 0
        private set

    /** @return 实际生效值（负值输入返回 0）。 */
    fun apply(offsetMs: Int): Int {
        val effective = offsetMs.coerceIn(0, AudioDelayProcessor.MAX_DELAY_MS)
        if (effective != lastEffectiveMs) {
            processor.setDelayMs(effective)
            lastEffectiveMs = effective
        }
        return effective
    }
}