package com.rythm.app.data.store

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缩略图文件存储（架构 5.5，R-12 压缩约束）：
 * - filesDir/marks/<markId>.jpg —— 帧标记缩略图（≤320px 宽）；
 * - filesDir/thumbnails/<docKey>.jpg —— 视频库列表缩略图（≤640px 宽）。
 * 删除视频时由级联流程调用 [deleteMarkFile]/[deleteThumbFile] 清理（I6）。
 */
open class ThumbnailStore(private val appContext: Context) {

    private val marksDir: File by lazy { File(appContext.filesDir, "marks").apply { mkdirs() } }
    private val thumbsDir: File by lazy { File(appContext.filesDir, "thumbnails").apply { mkdirs() } }

    open fun newMarkFile(markId: String): File = File(marksDir, "$markId.jpg")

    open fun newThumbFile(docKey: String): File = File(thumbsDir, sanitizeFileName(docKey) + ".jpg")

    /** 压缩保存 JPEG（R-12：宽 ≤ maxWidthPx）。成功返回 true。 */
    open suspend fun saveJpeg(bitmap: Bitmap, dest: File, maxWidthPx: Int = 320): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val scaled = if (bitmap.width > maxWidthPx) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        maxWidthPx,
                        (bitmap.height * maxWidthPx / bitmap.width).coerceAtLeast(1),
                        true,
                    )
                } else {
                    bitmap
                }
                dest.outputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            }.isSuccess
        }

    open fun deleteMarkFile(path: String) = runCatching { File(path).delete() }

    open fun deleteThumbFile(path: String) = runCatching { File(path).delete() }

    /** uri 字符串不能直接做文件名，用稳定 hex 键。 */
    private fun sanitizeFileName(key: String): String =
        Integer.toHexString(key.hashCode())
}