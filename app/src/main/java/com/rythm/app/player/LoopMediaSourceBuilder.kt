package com.rythm.app.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.LoopingMediaSource
import androidx.media3.exoplayer.source.MediaSource

/**
 * 媒体源构建（架构 P1-6）：连续循环 = `ClippingMediaSource(original, A', B')` → `LoopingMediaSource`。
 * **每次调用整链重建**（新 original 实例），禁止复用旧源（避免段落级缓存交叉，P1-6）。
 * 单次区间/无选区模式返回原片源（SelectionMonitor 负责拦截 B'，I2）。
 */
class LoopMediaSourceBuilder(
    private val baseFactory: MediaSource.Factory,
) {

    fun build(
        uri: Uri,
        startMs: Long?,
        endMs: Long?,
        continuousLoop: Boolean,
    ): MediaSource {
        val original = baseFactory.createMediaSource(MediaItem.fromUri(uri))
        if (!continuousLoop || startMs == null || endMs == null || startMs >= endMs) {
            return original
        }
        return LoopingMediaSource(
            ClippingMediaSource(original, startMs * 1000, endMs * 1000),
        )
    }
}