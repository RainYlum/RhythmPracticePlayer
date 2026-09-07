package com.rythm.app.ui.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rythm.app.model.PlayerPhase
import com.rythm.app.viewmodel.BookmarkViewModel
import com.rythm.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

/**
 * 播放页三态容器（详细设计 3.4.2）：CONTROL / COUNTDOWN / IMMERSIVE。
 *
 * - 视频输出：SurfaceView → ViewModel 代理绑定 ExoPlayer（UI 层不接触 media3，包边界）；
 * - 时间外推：Choreographer 每帧 `extrapolatedDisplayPositionMs`（AC-02.3），仅时间/进度组件消费；
 * - 方向适配：rotation 90/270 → portrait，其余 landscape（FR-02.9），configChanges 不重建；
 * - 生命周期：ON_STOP → 保存历史 + 退倒计时（FR-07）；返回 = 退出（历史由 ON_STOP 挂钩保存）；
 * - 练习模式：播放/倒计时中 startLockTask 屏蔽系统导航（边缘滑退、导航键、上滑任务管理），暂停/退出解锁。
 */
@Composable
fun PlayerScreen(
    uri: String,
    displayName: String,
    playerViewModel: PlayerViewModel,
    bookmarkViewModel: BookmarkViewModel,
    onExit: () -> Unit,
) {
    val state by playerViewModel.uiState.collectAsStateWithLifecycle()
    val captions by playerViewModel.activeCaptions.collectAsStateWithLifecycle()
    val marks by bookmarkViewModel.marks.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val view = LocalView.current
    var showBookmarks by remember { mutableStateOf(false) }

    // 每帧外推时钟（AC-02.3）：Choreographer 驱动，仅时间文本/进度消费（详细设计 §7）
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nowMs = it / 1_000_000L }
        }
    }

    // 装载 + 书签数据源绑定（FR-06）
    LaunchedEffect(uri) {
        playerViewModel.loadVideo(Uri.parse(uri), restore = true)
        bookmarkViewModel.attach(uri)
    }

    // 方向适配（FR-02.9）：每次 load 后 rotation 事件驱动一次
    LaunchedEffect(state.videoRotationDeg) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        activity.requestedOrientation = if (state.videoRotationDeg == 90 || state.videoRotationDeg == 270) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // 沉浸态系统栏隐藏 / 退出恢复（I3）
    LaunchedEffect(state.immersive) {
        val controller = WindowInsetsControllerCompat(
            (context as? android.app.Activity)?.window ?: return@LaunchedEffect,
            view,
        )
        if (state.immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ON_STOP：保存归一化历史 + 退倒计时（FR-07，详细设计 4.4）
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playerViewModel.onStop() }

    // 练习模式任务锁定（屏蔽导航手势/导航键/上滑任务管理）：播放中或倒计时中锁定，暂停即解锁
    val inPractice = state.isPlaying || state.isCountdown
    LaunchedEffect(inPractice) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        if (inPractice) activity.startLockTask() else activity.stopLockTask()
    }

    // 退出前解锁任务（防锁任务残留至 Activity 重建/销毁）
    val exitPractice: () -> Unit = {
        (context as? android.app.Activity)?.stopLockTask()
        onExit()
    }

    BackHandler { exitPractice() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 视频输出（居中 FIT：ExoPlayer 端 SCALE_TO_FIT 已配，FR-02.9）
        VideoSurface(playerViewModel, Modifier.fillMaxSize())

        when {
            state.phase == PlayerPhase.LOADING -> LoadingLayer()
            state.isCountdown -> CountdownOverlay(
                remainingMs = state.countdownRemainingMs,
                onCancel = { playerViewModel.cancelCountdown() },
            )
            state.immersive -> ImmersiveLayer(
                captions = captions,
                buffering = state.phase == PlayerPhase.BUFFERING,
                onExitImmersive = { playerViewModel.exitImmersive() },
            )
            state.phase == PlayerPhase.ERROR -> ErrorLayer(
                message = state.error?.message ?: "播放失败",
                onExit = exitPractice,
            )
            state.controlVisible -> PlayerControlsPanel(
                state = state,
                nowMs = nowMs,
                displayName = displayName,
                marks = marks,
                onPlay = { playerViewModel.play() },
                onPause = { playerViewModel.pause() },
                onSeek = { playerViewModel.commitScrub(it) },
                onScrub = { playerViewModel.scrubTo(it) },
                onTapLoopBand = { state.loopStartMs?.let { playerViewModel.seekTo(it) } },
                onStepBack = { playerViewModel.stepBack(it) },
                onStepFwd = { playerViewModel.stepFwd(it) },
                onSpeed = { playerViewModel.setSpeed(it) },
                onQuickLoop = { playerViewModel.setQuickLoop() },
                onClearSelection = { playerViewModel.clearSelection() },
                onNudgeA = { playerViewModel.nudgeLoopA(it) },
                onNudgeB = { playerViewModel.nudgeLoopB(it) },
                onToggleContinuousLoop = { playerViewModel.toggleContinuousLoop() },
                onCountdownSec = { playerViewModel.setCountdownSec(it) },
                onToggleShowMillis = { playerViewModel.toggleShowMillis() },
                onAudioOffset = { playerViewModel.setAudioOffsetMs(it) },
                onAddMark = { text -> playerViewModel.addFrameMark(text) },
                onOpenBookmarks = { showBookmarks = true },
                onExit = exitPractice,
            )
        }

        if (showBookmarks) {
            BookmarkPanel(
                marks = marks,
                onClose = { showBookmarks = false },
                onJumpTo = { ms ->
                    playerViewModel.seekTo(ms) // 点击标记：跳转定格（AC-06.2）
                    showBookmarks = false
                },
                onEditText = { id, text -> bookmarkViewModel.updateText(id, text) },
                onDelete = { id -> bookmarkViewModel.remove(id) },
                onAdd = { text -> playerViewModel.addFrameMark(text) },
            )
        }
    }
}

/** SurfaceView → ViewModel.attachVideoSurface（media3 边界在 player 层，UI 只传 Surface）。 */
@Composable
private fun VideoSurface(playerViewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        playerViewModel.attachVideoSurface(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        playerViewModel.attachVideoSurface(null)
                    }
                })
            }
        },
    )
}

@Composable
private fun LoadingLayer() {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

/**
 * 沉浸层（I3）：仅 ExitButton + 字幕提示；全屏手势丢弃容器消费点击/滑动（AC-02.6）。
 * ExitButton 以 zIndex 置顶保证可命中。双击 300ms 窗口退出（FR-02.7）。
 * internal 暴露供 androidTest 冒烟（沉浸仅 ExitButton / 双击窗口）。
 */
@Composable
internal fun ImmersiveLayer(
    captions: List<String>,
    buffering: Boolean,
    onExitImmersive: () -> Unit,
) {
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var showHint by remember { mutableStateOf(false) }

    fun onScreenTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapAt <= 300) {
            lastTapAt = 0L
            onExitImmersive()
        } else {
            lastTapAt = now
            showHint = true
        }
    }

    LaunchedEffect(showHint) {
        if (showHint) {
            delay(1_200)
            showHint = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 手势丢弃容器：消费一切点击/滑动（含字幕穿透事件），仅过滤双击退出
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onScreenTap() })
                }
                .pointerInput(Unit) {
                    // 滑动等其余手势一律消费（AC-02.6：播放中一切交互丢弃）
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        )
        // 非交互字幕层（不消费手势 → 事件落入丢弃容器）
        MarkCaptionOverlay(captions, Modifier.align(androidx.compose.ui.Alignment.TopCenter))
        // 缓冲圈（P1-9：播放中缓冲仅进度圈，不遮控制）
        if (buffering) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                color = Color.White,
            )
        }
        // 双击退出浮层提示（轻反馈）：小尺寸置顶，避免遮挡画面中央（练习模式）
        if (showHint) {
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .padding(top = 24.dp),
                color = Color(0xAA000000),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) {
                androidx.compose.material3.Text(
                    text = "再点一次退出",
                    color = Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        // ExitButton（zIndex 置顶于丢弃容器，保证可命中；双击语义由容器统一判定）
        androidx.compose.material3.IconButton(
            onClick = ::onScreenTap,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopStart)
                .padding(8.dp)
                .zIndex(1f),
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "退出沉浸",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ErrorLayer(message: String, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Text(
                text = message,
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onExit) { androidx.compose.material3.Text("返回") }
        }
    }
}