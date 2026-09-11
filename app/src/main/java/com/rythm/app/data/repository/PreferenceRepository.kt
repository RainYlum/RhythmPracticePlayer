package com.rythm.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rythm.app.model.AppSettings
import com.rythm.app.model.HistoryEntry
import com.rythm.app.model.OnboardingStatus
import com.rythm.app.model.PlaybackSpeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 播放历史 + 全局设置（架构 8.1）。
 * 键 = `history.<uri>`（uri 字符串作键，P1-8 弃 hash）；上限 50 条 LRU 淘汰；
 * positionMs 为归一化值（I5）。
 */
interface PreferenceRepository {

    val settings: Flow<AppSettings>

    /** 教学引导状态（三阶段完成标记，视频库「再次教学」可整体重置）。 */
    val onboarding: Flow<OnboardingStatus>

    suspend fun saveHistory(entry: HistoryEntry)

    suspend fun loadHistory(uri: String): HistoryEntry?

    suspend fun removeHistory(uri: String)

    suspend fun setDefaultSpeed(speed: PlaybackSpeed)

    suspend fun setCountdownSec(sec: Int)

    suspend fun setIntroDone(done: Boolean)

    suspend fun setPlayerTutorialDone(done: Boolean)

    suspend fun setMoreMenuTutorialDone(done: Boolean)

    /** 重置全部教学标记（视频库「再次教学」确认后调用）。 */
    suspend fun resetOnboarding()
}

class PreferenceRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : PreferenceRepository {

    override val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            defaultSpeed = PlaybackSpeed.from(p[KEY_DEFAULT_SPEED] ?: PlaybackSpeed.DEFAULT.value),
            countdownSec = p[KEY_COUNTDOWN_SEC] ?: 3,
            keepScreenOn = p[KEY_KEEP_SCREEN_ON] ?: false,
        )
    }

    override val onboarding: Flow<OnboardingStatus> = dataStore.data.map { p ->
        OnboardingStatus(
            introDone = p[KEY_INTRO_DONE] ?: false,
            playerTutorialDone = p[KEY_PLAYER_TUTORIAL_DONE] ?: false,
            moreMenuTutorialDone = p[KEY_MORE_MENU_TUTORIAL_DONE] ?: false,
        )
    }

    override suspend fun saveHistory(entry: HistoryEntry) {
        dataStore.edit { prefs ->
            prefs[historyKey(entry.uri)] = json.encodeToString(HistoryEntry.serializer(), entry)
            trimHistoryLocked(prefs)
        }
    }

    override suspend fun loadHistory(uri: String): HistoryEntry? =
        dataStore.data.first()[historyKey(uri)]?.let { raw ->
            runCatching {
                json.decodeFromString(HistoryEntry.serializer(), raw)
            }.getOrNull()
        }

    override suspend fun removeHistory(uri: String) {
        dataStore.edit { it.remove(historyKey(uri)) }
    }

    override suspend fun setDefaultSpeed(speed: PlaybackSpeed) {
        dataStore.edit { it[KEY_DEFAULT_SPEED] = speed.value }
    }

    override suspend fun setCountdownSec(sec: Int) {
        dataStore.edit { it[KEY_COUNTDOWN_SEC] = sec.coerceIn(0, 5) }
    }

    override suspend fun setIntroDone(done: Boolean) {
        dataStore.edit { it[KEY_INTRO_DONE] = done }
    }

    override suspend fun setPlayerTutorialDone(done: Boolean) {
        dataStore.edit { it[KEY_PLAYER_TUTORIAL_DONE] = done }
    }

    override suspend fun setMoreMenuTutorialDone(done: Boolean) {
        dataStore.edit { it[KEY_MORE_MENU_TUTORIAL_DONE] = done }
    }

    override suspend fun resetOnboarding() {
        dataStore.edit {
            it[KEY_INTRO_DONE] = false
            it[KEY_PLAYER_TUTORIAL_DONE] = false
            it[KEY_MORE_MENU_TUTORIAL_DONE] = false
        }
    }

    /** history.* 键超过上限时按 updatedAt 淘汰最旧条目（架构 8.1，LRU）。 */
    private fun trimHistoryLocked(prefs: MutablePreferences) {
        val historyKeys = prefs.asMap().keys.filter { it.name.startsWith(HISTORY_PREFIX) }
        if (historyKeys.size <= MAX_HISTORY) return
        val newestFirst = historyKeys.mapNotNull { key ->
            val raw = prefs[key] as? String
            val updatedAt = raw?.let {
                runCatching { json.decodeFromString(HistoryEntry.serializer(), it).updatedAt }
                    .getOrNull()
            } ?: 0L
            key to updatedAt
        }.sortedByDescending { it.second }
        newestFirst.drop(MAX_HISTORY).forEach { prefs.remove(it.first) }
    }

    private fun historyKey(uri: String): Preferences.Key<String> =
        stringPreferencesKey(HISTORY_PREFIX + uri)

    companion object {
        private const val HISTORY_PREFIX = "history."
        private const val MAX_HISTORY = 50

        val KEY_DEFAULT_SPEED = floatPreferencesKey("settings.default_speed")
        val KEY_COUNTDOWN_SEC = intPreferencesKey("settings.countdown_sec")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("settings.keep_screen_on")

        val KEY_INTRO_DONE = booleanPreferencesKey("onboarding.intro_done")
        val KEY_PLAYER_TUTORIAL_DONE = booleanPreferencesKey("onboarding.player_tutorial_done")
        val KEY_MORE_MENU_TUTORIAL_DONE = booleanPreferencesKey("onboarding.more_menu_tutorial_done")
    }
}