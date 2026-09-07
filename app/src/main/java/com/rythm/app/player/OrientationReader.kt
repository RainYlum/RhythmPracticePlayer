package com.rythm.app.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 方向读取（OrientationAdapter，R-11）：METADATA_KEY_VIDEO_ROTATION 读取画面旋转角。
 * 画面旋转已由渲染层自动处理（Media3 解码输出已应用 rotation），本类职责仅 =
 * Activity 方向决策 + UI 布局输入；缺失/不可读返回 0（横屏兜底）。
 */
class OrientationReader(private val appContext: Context) {

    suspend fun readRotation(uri: Uri): Int = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }
        }.getOrDefault(0)
    }
}