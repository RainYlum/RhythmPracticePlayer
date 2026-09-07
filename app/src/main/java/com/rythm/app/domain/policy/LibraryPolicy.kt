package com.rythm.app.domain.policy

/**
 * 视频库去重规则（FR-11.5，架构 8.2）：
 * 同一源文件以 DocumentsContract.getDocumentId 归一（优先于 uri 字符串比较，
 * 兼容同文件经不同授权路径产生的 uri 分裂）。
 *
 * 纯 Kotlin：uri 以字符串传入，docId 解析由 data 层注入（需要 resolver 访问）。
 */
object LibraryPolicy {

    /** 归一化文档 ID：优先 documentId，解析失败时退回 uri 字符串本身。 */
    fun normalizeDocId(uriString: String, resolveDocId: (String) -> String?): String =
        resolveDocId(uriString) ?: uriString

    fun shouldDedupe(existingDocId: String, newDocId: String): Boolean =
        existingDocId == newDocId
}