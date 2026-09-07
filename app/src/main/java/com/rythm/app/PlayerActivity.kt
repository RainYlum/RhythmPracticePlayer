package com.rythm.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.rythm.app.ui.player.PlayerScreen
import com.rythm.app.ui.theme.RhythmPracticePlayerTheme
import com.rythm.app.viewmodel.BookmarkViewModel
import com.rythm.app.viewmodel.PlayerViewModel
import com.rythm.app.viewmodel.ViewModelFactory

/**
 * 播放页（FR-02）：方向由代码按视频 rotation 控制（FR-02.9），
 * manifest configChanges 防旋转重建保播放连续（详细设计 3.4 / 阶段 5）。
 * uri 经 intent extra 传递；ON_STOP 历史保存由 PlayerScreen 生命周期挂钩完成。
 */
class PlayerActivity : ComponentActivity() {

    private val factory: ViewModelFactory by lazy {
        ViewModelFactory((application as RhythmApp).container)
    }

    private val playerViewModel: PlayerViewModel by viewModels { factory }
    private val bookmarkViewModel: BookmarkViewModel by viewModels { factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            // 播放页固定深色（视频场景 I3 沉浸态要求深底）
            RhythmPracticePlayerTheme(darkTheme = true) {
                PlayerScreen(
                    uri = uriString,
                    playerViewModel = playerViewModel,
                    bookmarkViewModel = bookmarkViewModel,
                    onExit = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_URI = "uri"

        fun start(context: Context, uri: String) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_URI, uri),
            )
        }
    }
}