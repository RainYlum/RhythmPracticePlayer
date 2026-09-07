package com.rythm.app.model

import kotlinx.serialization.Serializable

/**
 * 播放历史条目（架构 8.1，详细设计 3.3.2）。
 * @property positionMs 归一化播放位置（连续循环态下为圈内偏移换算后的真实时间轴值，I5）。
 * @property loopStartMs/loopEndMs 对齐关键帧后的真实边界 A'/B'（非用户原始标定值）。
 */
@Serializable
data class HistoryEntry(
    val uri: String,
    val positionMs: Long = 0L,
    val speed: PlaybackSpeed = PlaybackSpeed.DEFAULT,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val loopEnabled: Boolean = false,
    val continuousLoop: Boolean = false,
    val countdownSec: Int = 3,
    val audioOffsetMs: Int = 0,
    val updatedAt: Long = 0L,
)