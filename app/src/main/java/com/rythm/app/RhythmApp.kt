package com.rythm.app

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.rythm.app.data.repository.BookmarkRepository
import com.rythm.app.data.repository.BookmarkRepositoryImpl
import com.rythm.app.data.repository.PreferenceRepository
import com.rythm.app.data.repository.PreferenceRepositoryImpl
import com.rythm.app.data.repository.VideoLibraryRepository
import com.rythm.app.data.repository.VideoLibraryRepositoryImpl
import com.rythm.app.data.repository.VideoRepository
import com.rythm.app.data.repository.VideoRepositoryImpl
import com.rythm.app.data.store.ThumbnailStore
import com.rythm.app.player.FrameGrabber
import com.rythm.app.player.VideoPlayerManager
import com.rythm.app.player.VideoPlayerManagerImpl

/**
 * 顶层单例 DataStore（RythmApp.onCreate 前即可用）。
 * 存储：播放历史、设置、视频库条目、帧标记（键均以 uri.toString() 为主键，见架构 8.1）。
 */
private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "rhythm_settings")

class RhythmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * 手写轻量 DI 容器（ADR-03 决策：以构造注入替代 Hilt，单模块项目足够）。
 * 供所有 ViewModel 通过 ViewModelFactory 获取依赖；player 层经容器工厂创建实例。
 */
class AppContainer(private val app: Application) {

    val dataStore: DataStore<Preferences>
        get() = app.appDataStore

    // ==== data 层 ====
    val thumbStore: ThumbnailStore by lazy { ThumbnailStore(app) }

    val videoRepository: VideoRepository by lazy { VideoRepositoryImpl(app) }

    /** 帧抓取（播放页帧标记 + 视频库列表缩略图，无状态可共享）。 */
    val frameGrabber: FrameGrabber by lazy { FrameGrabber(app) }

    val preferenceRepository: PreferenceRepository by lazy {
        PreferenceRepositoryImpl(dataStore)
    }

    val bookmarkRepository: BookmarkRepository by lazy {
        BookmarkRepositoryImpl(dataStore, thumbStore)
    }

    val videoLibraryRepository: VideoLibraryRepository by lazy {
        VideoLibraryRepositoryImpl(
            dataStore = dataStore,
            bookmarkRepository = bookmarkRepository,
            preferenceRepository = preferenceRepository,
            thumbStore = thumbStore,
        )
    }

    // ==== player 层（工厂：每个播放会话一个实例，释放后不可复用） ====
    val playerManagerFactory: (() -> VideoPlayerManager) by lazy { { VideoPlayerManagerImpl(app) } }
}