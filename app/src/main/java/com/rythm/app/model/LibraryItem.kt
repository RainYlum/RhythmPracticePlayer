package com.rythm.app.model

import kotlinx.serialization.Serializable

/**
 * 视频库条目（详细设计 3.3.2；uri 字符串作主键，架构 P1-8 弃 hash 键）。
 * @property docId DocumentsContract.getDocumentId，用于去重（FR-11.5）。
 */
@Serializable
data class LibraryItem(
    val uri: String,
    val docId: String,
    val displayName: String,
    val note: String? = null,
    val thumbPath: String? = null,
    val addedAt: Long = 0L,
    val lastOpenedAt: Long = 0L,
)