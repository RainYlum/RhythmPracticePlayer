package com.rythm.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rythm.app.data.store.ThumbnailStore
import com.rythm.app.model.FrameMark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 帧标记 / 书签（FR-06，v1.3 升级）：按视频关联存储，键 = `bookmarks.<uri>`。
 * timeMs 为归一化时间轴值（I5）；缩略图文件存 filesDir/marks/，删除时联动清理（I6）。
 */
interface BookmarkRepository {

    fun listFlow(uri: String): Flow<List<FrameMark>>

    suspend fun list(uri: String): List<FrameMark>

    suspend fun add(uri: String, mark: FrameMark)

    suspend fun update(uri: String, mark: FrameMark)

    suspend fun remove(uri: String, markId: String)

    /** 删除视频级联调用：清空条目并删除该视频全部标记缩略图文件（AC-06.4/11.4）。 */
    suspend fun removeAll(uri: String)
}

class BookmarkRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val thumbStore: ThumbnailStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : BookmarkRepository {

    override fun listFlow(uri: String): Flow<List<FrameMark>> = dataStore.data.map { p ->
        p[key(uri)]?.let { raw ->
            runCatching {
                json.decodeFromString<List<FrameMark>>(raw)
            }.getOrNull() ?: emptyList()
        } ?: emptyList()
    }

    override suspend fun list(uri: String): List<FrameMark> = listFlow(uri).first()

    override suspend fun add(uri: String, mark: FrameMark) {
        dataStore.edit { p -> p[key(uri)] = json.encodeToString(list(uri) + mark) }
    }

    override suspend fun update(uri: String, mark: FrameMark) {
        dataStore.edit { p ->
            val updated = list(uri).map { if (it.id == mark.id) mark else it }
            p[key(uri)] = json.encodeToString(updated)
        }
    }

    override suspend fun remove(uri: String, markId: String) {
        var removedPath: String? = null
        dataStore.edit { p ->
            val marks = list(uri)
            removedPath = marks.firstOrNull { it.id == markId }?.thumbPath
            p[key(uri)] = json.encodeToString(marks.filterNot { it.id == markId })
        }
        removedPath?.let { thumbStore.deleteMarkFile(it) }
    }

    override suspend fun removeAll(uri: String) {
        val paths = list(uri).map { it.thumbPath }
        dataStore.edit { it.remove(key(uri)) }
        paths.forEach { thumbStore.deleteMarkFile(it) }
    }

    private fun key(uri: String): Preferences.Key<String> = stringPreferencesKey("bookmarks.$uri")
}