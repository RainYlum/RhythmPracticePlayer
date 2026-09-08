package com.rythm.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.rythm.app.ui.library.VideoLibraryScreen
import com.rythm.app.ui.theme.RhythmPracticePlayerTheme
import com.rythm.app.viewmodel.LibraryViewModel
import com.rythm.app.viewmodel.ViewModelFactory

/**
 * 主页（FR-11）：视频库列表；支持旋转（随用户屏幕方向变化）。导入 → 播放页跳转均经 VideoLibraryScreen。
 */
class MainActivity : ComponentActivity() {

    private val factory: ViewModelFactory by lazy {
        ViewModelFactory((application as RhythmApp).container)
    }

    private val libraryViewModel: LibraryViewModel by viewModels { factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RhythmPracticePlayerTheme {
                VideoLibraryScreen(
                    viewModel = libraryViewModel,
                    onOpenVideo = { uri, _ ->
                        PlayerActivity.start(this, uri)
                    },
                )
            }
        }
    }
}