package com.rythm.app.model

/**
 * 视频元数据（架构 5.5 VideoRepository 产出）。
 * frameRate 部分容器/VFR 不可得时为 null（降级 30fps 近似，FR-05.3）。
 */
data class VideoMetadata(
    val durationMs: Long,
    val frameRate: Float?,
    val rotationDeg: Int,
)