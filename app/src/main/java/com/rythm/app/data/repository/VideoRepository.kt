package com.rythm.app.data.repository

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.rythm.app.model.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视频源访问（架构 5.5）：SAF 持久授权、元数据读取、授权可达性校验。
 * 全部只读，不落本地副本（NFR-08.3）。
 */
interface VideoRepository {

    /** 选择文件成功后立即调用（架构 8.2）；第三方 provider 不支持 persistable 时静默忽略。 */
    fun takePersistablePermission(uri: Uri)

    /**
     * 读取元数据（IO 线程）。不可读/损坏时抛异常（调用方提示"无法播放该视频"，FR-01.2 AC-01.3）。
     */
    suspend fun readMetadata(uri: Uri): VideoMetadata

    /** 授权可达性校验（架构 8.2）：尝试打开流读 1 字节。 */
    suspend fun isUriReadable(uri: Uri): Boolean

    /** 文档 ID 归一（FR-11.5）：非 documents uri 返回 null，由 LibraryPolicy 退回 uri 字符串。 */
    fun resolveDocId(uri: Uri): String?
}

class VideoRepositoryImpl(private val context: Context) : VideoRepository {

    override fun takePersistablePermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // 第三方文件 provider 常不支持 persistable → 靠 isUriReadable 兜底（架构 8.2）
        }
    }

    override suspend fun readMetadata(uri: Uri): VideoMetadata = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) error("duration unavailable")
            val frameRate = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
            val rotationDeg = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            VideoMetadata(
                durationMs = durationMs,
                frameRate = frameRate,
                rotationDeg = rotationDeg,
            )
        } finally {
            retriever.release()
        }
    }

    override suspend fun isUriReadable(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext false
            input.use { it.read() != -1 }
        } catch (e: Exception) {
            false
        }
    }

    override fun resolveDocId(uri: Uri): String? = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: Exception) {
        null
    }
}