package com.rythm.app.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 帧画面抓取（FR-06 帧标记缩略图 + FR-10 倒计时静止帧遮罩 + 视频库列表缩略图，路径 A）：
 * MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST) 于 IO 线程执行，压缩 ≤maxWidthPx 宽（R-12）。
 * 失败（不可读/无帧）返回 null，调用方降级（删除不成立 / 深色遮罩 / 列表占位块）。
 * open 供单测 Fake 覆写（与 ThumbnailStore 同风格，FR：库缩略图生成也可复用于旧库补全）。
 */
open class FrameGrabber(private val appContext: Context) {

    open suspend fun grabFrame(uri: Uri, positionMs: Long, maxWidthPx: Int = 320): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(appContext, uri)
                    val frame = retriever.getFrameAtTime(
                        positionMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    ) ?: return@runCatching null
                    if (frame.width <= maxWidthPx) {
                        frame
                    } else {
                        val scaled = Bitmap.createScaledBitmap(
                            frame,
                            maxWidthPx,
                            (frame.height * maxWidthPx.toFloat() / frame.width).toInt().coerceAtLeast(1),
                            true,
                        )
                        frame.recycle()
                        scaled
                    }
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
}