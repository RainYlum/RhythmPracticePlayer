package com.rythm.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rythm.app.AppContainer

/**
 * 手写 ViewModel 工厂（ADR-03：构造注入替代 Hilt）。
 * UI 侧用 `viewModels { viewModelFactory }` 获取；AppContainer 实例来自 RhythmApp。
 */
class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(PlayerViewModel::class.java) ->
            PlayerViewModel(
                videoRepository = container.videoRepository,
                preferenceRepository = container.preferenceRepository,
                videoLibraryRepository = container.videoLibraryRepository,
                bookmarkRepository = container.bookmarkRepository,
                thumbStore = container.thumbStore,
                playerManagerFactory = container.playerManagerFactory,
            ) as T

        modelClass.isAssignableFrom(LibraryViewModel::class.java) ->
            LibraryViewModel(
                libraryRepository = container.videoLibraryRepository,
                videoRepository = container.videoRepository,
                thumbStore = container.thumbStore,
                frameGrabber = container.frameGrabber,
            ) as T

        modelClass.isAssignableFrom(BookmarkViewModel::class.java) ->
            BookmarkViewModel(
                bookmarkRepository = container.bookmarkRepository,
                thumbStore = container.thumbStore,
            ) as T

        else -> error("未知 ViewModel: ${modelClass.name}")
    }
}