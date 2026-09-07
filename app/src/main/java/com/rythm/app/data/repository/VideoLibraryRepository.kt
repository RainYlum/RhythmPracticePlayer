package com.rythm.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rythm.app.data.store.ThumbnailStore
import com.rythm.app.domain.policy.LibraryPolicy
import com.rythm.app.model.LibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 视频库条目（FR-11，v1.3）：键 = `library.<uri>`（uri 字符串作主键，架构 P1-8）。
 * 删除 = 级联清除 bookmarks + marks 缩略图 + history + 库条目 + 视频缩略图（I6，AC-11.4），
 * 绝不删除用户源文件（SAF 只读）。
 */
interface VideoLibraryRepository {

    /** 列表数据源（按 addedAt 倒序，最近导入在前，FR-11.1）。 */
    fun listFlow(): Flow<List<LibraryItem>>

    suspend fun list(): List<LibraryItem>

    suspend fun getItem(uri: String): LibraryItem?

    /** 导入登记（FR-01.5）：docId 已存在则复用条目仅刷新 lastOpenedAt，返回是否新增。 */
    suspend fun add(uri: String, displayName: String, docId: String): Boolean

    suspend fun updateNote(uri: String, note: String?)

    suspend fun updateLastOpened(uri: String, at: Long)

    suspend fun updateThumb(uri: String, thumbPath: String)

    /** 级联删除（详细设计 6.2 执行顺序）。 */
    suspend fun deleteVideo(uri: String)
}

class VideoLibraryRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val bookmarkRepository: BookmarkRepository,
    private val preferenceRepository: PreferenceRepository,
    private val thumbStore: ThumbnailStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : VideoLibraryRepository {

    override fun listFlow(): Flow<List<LibraryItem>> = dataStore.data.map { p ->
        p.asMap().keys
            .filter { it.name.startsWith(LIBRARY_PREFIX) }
            .mapNotNull { key ->
                (p[key] as? String)?.let { raw ->
                    runCatching { json.decodeFromString<LibraryItem>(raw) }.getOrNull()
                }
            }
            .sortedByDescending { it.addedAt }
    }

    override suspend fun list(): List<LibraryItem> = listFlow().first()

    override suspend fun getItem(uri: String): LibraryItem? =
        list().firstOrNull { it.uri == uri }

    override suspend fun add(uri: String, displayName: String, docId: String): Boolean {
        val now = System.currentTimeMillis()
        var added = false
        dataStore.edit { prefs ->
            val existing = prefs.asMap().keys
                .filter { it.name.startsWith(LIBRARY_PREFIX) }
                .mapNotNull { key ->
                    (prefs[key] as? String)?.let { raw ->
                        runCatching { json.decodeFromString<LibraryItem>(raw) }.getOrNull()
                    }
                }
            val dup = existing.firstOrNull { LibraryPolicy.shouldDedupe(it.docId, docId) }
            if (dup != null) {
                // FR-11.5 去重：复用条目，刷新打开时间（保留原 uri 以维持 history/bookmarks 关联）
                prefs[key(dup.uri)] = json.encodeToString(dup.copy(lastOpenedAt = now))
            } else {
                prefs[key(uri)] = json.encodeToString(
                    LibraryItem(
                        uri = uri,
                        docId = docId,
                        displayName = displayName,
                        addedAt = now,
                        lastOpenedAt = now,
                    ),
                )
                added = true
            }
        }
        return added
    }

    override suspend fun updateNote(uri: String, note: String?) {
        dataStore.edit { prefs ->
            val item = prefs[key(uri)]?.let { runCatching { json.decodeFromString<LibraryItem>(it) }.getOrNull() }
            if (item != null) prefs[key(uri)] = json.encodeToString(item.copy(note = note))
        }
    }

    override suspend fun updateLastOpened(uri: String, at: Long) {
        dataStore.edit { prefs ->
            val item = prefs[key(uri)]?.let { runCatching { json.decodeFromString<LibraryItem>(it) }.getOrNull() }
            if (item != null) prefs[key(uri)] = json.encodeToString(item.copy(lastOpenedAt = at))
        }
    }

    override suspend fun updateThumb(uri: String, thumbPath: String) {
        dataStore.edit { prefs ->
            val item = prefs[key(uri)]?.let { runCatching { json.decodeFromString<LibraryItem>(it) }.getOrNull() }
            if (item != null) prefs[key(uri)] = json.encodeToString(item.copy(thumbPath = thumbPath))
        }
    }

    /**
     * 级联删除（详细设计 6.2 顺序）：
     * ① bookmarks.<uri> 清除 + marks/ 缩略图文件删除；
     * ② history.<uri> 清除；
     * ③ library.<uri> 清除 + thumbnails/ 视频缩略图删除。
     */
    override suspend fun deleteVideo(uri: String) {
        val item = getItem(uri)
        bookmarkRepository.removeAll(uri)
        preferenceRepository.removeHistory(uri)
        dataStore.edit { it.remove(key(uri)) }
        item?.thumbPath?.let { thumbStore.deleteThumbFile(it) }
    }

    private fun key(uri: String): Preferences.Key<String> = stringPreferencesKey(LIBRARY_PREFIX + uri)

    companion object {
        private const val LIBRARY_PREFIX = "library."
    }
}