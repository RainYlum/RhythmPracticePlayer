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
 * 生命周期：ON_STOP → 保存历史 + 退倒计时（FR-07）；返回 = 弹确认后退出（历史由 ON_STOP 挂钩保存）。
 * - 练习模式：沉浸隐藏系统栏 + 返回键分派（倒计时=取消、练习中=两次手势退出沉浸、控制态=退出页），
 *   侧滑返回/左上角按钮/点击屏幕共享同一两次手势窗口（与双击语义一致，防误触）；
 *   退出练习（控制态返回/错误层返回）先弹确认对话框，确认后才退至视频库。
 *   不使用 LockTask（避免固定屏幕弹窗/退出后锁屏副作用）；系统手势无法在应用层完全屏蔽。
 */
@Composable
fun PlayerScreen(
    uri: String,
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
    // 退出确认（FR：结束练习退出至视频库前弹窗确认，防误触退出）
    var showExitConfirm by remember { mutableStateOf(false) }
    // 控制面板功能区展开（本次练习内展开后不收起；Activity 销毁重建即重置）
    var panelExpanded by remember { mutableStateOf(false) }
    // 沉浸两次手势退出：首次手势（点屏幕/左上角/侧滑返回）仅提示，窗口内第二次才退出
    var exitHintVisible by remember { mutableStateOf(false) }
    var lastExitGestureAt by remember { mutableLongStateOf(0L) }

    fun onImmersiveExitGesture() {
        val now = System.currentTimeMillis()
        if (now - lastExitGestureAt <= EXIT_GESTURE_WINDOW_MS) {
            lastExitGestureAt = 0L
            exitHintVisible = false
            playerViewModel.exitImmersive()
        } else {
            lastExitGestureAt = now
            exitHintVisible = true
        }
    }

    // 退出前确认（仅弹窗，确认后由 onExit 真正退出；历史保存由 ON_STOP 挂钩完成）
    fun requestExit() { showExitConfirm = true }

    // 提示展示窗口：与手势判定窗口同长，到时自动收起（超时后需重新累计两次手势）
    LaunchedEffect(exitHintVisible) {
        if (exitHintVisible) {
            delay(EXIT_GESTURE_WINDOW_MS)
            exitHintVisible = false
        }
    }

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

    // 返回键分派（兼拦截边缘滑动返回手势）：倒计时 = 取消，练习播放 = 两次手势退出沉浸（暂停而非退应用），
    // 其余（控制态/错误） = 弹确认后退出播放页
    BackHandler {
        when {
            state.isCountdown -> playerViewModel.cancelCountdown()
            state.immersive -> onImmersiveExitGesture()
            else -> requestExit()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 视频输出（居中 FIT：ExoPlayer 端 SCALE_TO_FIT 已配，FR-02.9）
        VideoSurface(
            playerViewModel = playerViewModel,
            readySignal = state.videoUri,
            modifier = Modifier.fillMaxSize(),
        )

        when {
            state.phase == PlayerPhase.LOADING -> LoadingLayer()
            state.isCountdown -> CountdownOverlay(
                remainingMs = state.countdownRemainingMs,
                onCancel = { playerViewModel.cancelCountdown() },
            )
            state.immersive -> ImmersiveLayer(
                captions = captions,
                buffering = state.phase == PlayerPhase.BUFFERING,
                showExitHint = exitHintVisible,
                onExitGesture = ::onImmersiveExitGesture,
            )
            state.phase == PlayerPhase.ERROR -> ErrorLayer(
                message = state.error?.message ?: "播放失败",
                onExit = { requestExit() },
            )
            // 其余（READY / BUFFERING / ENDED / IDLE）一律显示控制面板：
            // 暂停态拖动进度条触发缓冲（BUFFERING）时面板不能消失，否则手势节点被移除导致拖动中断
            else -> PlayerControlsPanel(
                state = state,
                nowMs = nowMs,
                marks = marks,
                panelExpanded = panelExpanded,
                onExpandMore = { panelExpanded = true },
                onPlay = { playerViewModel.play() },
                onPause = { playerViewModel.pause() },
                onSeek = { playerViewModel.commitScrub(it) },
                onScrub = { playerViewModel.scrubTo(it) },
                onStepBack = { playerViewModel.stepBack(it) },
                onStepFwd = { playerViewModel.stepFwd(it) },
                onSpeed = { playerViewModel.setSpeed(it) },
                onQuickLoop = { playerViewModel.setQuickLoop() },
                onClearSelection = { playerViewModel.clearSelection() },
                onNudgeA = { playerViewModel.nudgeLoopA(it) },
                onNudgeB = { playerViewModel.nudgeLoopB(it) },
                onNudgeDragA = { playerViewModel.nudgeLoopADrag(it) },
                onNudgeDragB = { playerViewModel.nudgeLoopBDrag(it) },
                onToggleContinuousLoop = { playerViewModel.toggleContinuousLoop() },
                onCountdownSec = { playerViewModel.setCountdownSec(it) },
                onAudioOffset = { playerViewModel.setAudioOffsetMs(it) },
                onAddMark = { text -> playerViewModel.addFrameMark(text) },
                onOpenBookmarks = { showBookmarks = true },
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

        // 退出确认（返回键/错误层返回统一入口）：确认后退出至视频库（ON_STOP 保存历史）
        if (showExitConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showExitConfirm = false },
                title = { androidx.compose.material3.Text("结束练习") },
                text = { androidx.compose.material3.Text("确定结束本次练习并返回视频库？") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showExitConfirm = false
                        onExit()
                    }) { androidx.compose.material3.Text("退出") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showExitConfirm = false }) {
                        androidx.compose.material3.Text("取消")
                    }
                },
            )
        }
    }
}

/**
 * SurfaceView → ViewModel.attachVideoSurface（media3 边界在 player 层，UI 只传 Surface）。
 * 双保险重绑：以「surface + 引擎就绪信号（videoUri）」为 key 的 LaunchedEffect 重发绑定——
 * 首帧 surfaceCreated 若早于引擎创建，attach 为 no-op，引擎就绪后 videoUri 变化再次补绑，
 * 消除"首次进入画面全黑"的绑定竞态；surfaceDestroyed 显式解绑防泄漏。
 */
@Composable
private fun VideoSurface(
    playerViewModel: PlayerViewModel,
    readySignal: String?,
    modifier: Modifier = Modifier,
) {
    var surface by remember { mutableStateOf<android.view.Surface?>(null) }
    LaunchedEffect(surface, readySignal) {
        if (surface != null) playerViewModel.attachVideoSurface(surface)
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        surface = holder.surface
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        surface = null
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
 * ExitButton 与屏幕点击、侧滑返回统一走 [onExitGesture]（两次手势窗口由 PlayerScreen 判定，
 * FR-02.7：首次仅提示「再点一次退出」，窗口内第二次才退出）。
 * internal 暴露供 androidTest 冒烟（沉浸仅 ExitButton / 提示文案渲染）。
 */
@Composable
internal fun ImmersiveLayer(
    captions: List<String>,
    buffering: Boolean,
    showExitHint: Boolean,
    onExitGesture: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // 手势丢弃容器：消费一切点击/滑动（含字幕穿透事件），仅过滤退出手势
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onExitGesture() })
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
        // 两次手势退出浮层提示（轻反馈，R-10）：小尺寸置顶，避免遮挡画面中央（练习模式）
        if (showExitHint) {
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
        // ExitButton（zIndex 置顶于丢弃容器，保证可命中；两次手势语义由容器统一判定）
        // 黑色半透明圆形背景：白色叉号在亮色画面/白背景上保持可见
        androidx.compose.material3.IconButton(
            onClick = onExitGesture,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.TopStart)
                .padding(8.dp)
                .zIndex(1f)
                .background(Color(0x66000000), androidx.compose.foundation.shape.CircleShape),
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

/** 沉浸两次手势退出窗口（与提示展示同长）：首次手势只提示，窗口内第二次才退出（FR-02.7 防误触）。 */
private const val EXIT_GESTURE_WINDOW_MS = 1_200L