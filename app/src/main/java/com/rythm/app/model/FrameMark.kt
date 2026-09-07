package com.rythm.app.model

import kotlinx.serialization.Serializable

/**
 * 帧标记 / 书签（FR-06，详细设计 3.3.2）。
 * @property timeMs 归一化时间轴值（I5：位置/书签/历史全为归一化值）。
 * @property text 自定义文本，可空串；空文本仅作可跳转书签、不产生字幕提醒（FR-06.6）。
 * @property thumbPath filesDir/marks/ 下缩略图文件绝对路径。
 */
@Serializable
data class FrameMark(
    val id: String,
    val timeMs: Long,
    val text: String = "",
    val thumbPath: String = "",
    val createdAt: Long = 0L,
)