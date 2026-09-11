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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import com.rythm.app.model.OnboardingStatus
import com.rythm.app.model.PlayerPhase
import com.rythm.app.ui.common.TutorialOverlay
import com.rythm.app.ui.common.TutorialStep
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
    // 教学引导状态（首次进入播放页教学 / 首次展开更多功能教学）
    val onboarding by playerViewModel.onboarding.collectAsStateWithLifecycle()

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
    // 教学：目标控件坐标上报（key → 窗口坐标，与 overlay 同坐标系）+ 步骤索引（-1 = 未激活）
    val tutorialBounds = remember { mutableStateMapOf<String, Rect>() }
    val panelScrollState = rememberScrollState()
    var playerTutorialIndex by remember { mutableIntStateOf(-1) }
    var moreTutorialIndex by remember { mutableIntStateOf(-1) }

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
    // 其余（控制态/错误） = 弹确认后退出播放页；教学激活时吞掉返回键（须点完教学步骤）
    BackHandler {
        if (playerTutorialIndex >= 0 || moreTutorialIndex >= 0) return@BackHandler
        when {
            state.isCountdown -> playerViewModel.cancelCountdown()
            state.immersive -> onImmersiveExitGesture()
            else -> requestExit()
        }
    }

    // 播放页教学（首次进入播放页且未完成）：READY 控制态触发，面板滚动回顶部保证「更多功能」入口可见
    LaunchedEffect(state.phase, state.isPlaying, onboarding.playerTutorialDone) {
        if (playerTutorialIndex < 0 &&
            state.phase == PlayerPhase.READY && !state.isPlaying && !onboarding.playerTutorialDone
        ) {
            panelScrollState.scrollTo(0)
            playerTutorialIndex = 0
        }
    }

    // 更多功能教学：按目标控件所在行滚动面板，保证被讲解的按键可见
    LaunchedEffect(moreTutorialIndex) {
        if (moreTutorialIndex >= 0) {
            panelScrollState.animateScrollTo(if (moreTutorialIndex <= 1) 0 else panelScrollState.maxValue)
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
                tutorialBounds = tutorialBounds,
                panelScrollState = panelScrollState,
                onExpandMore = {
                    panelExpanded = true
                    // 首次展开「更多功能」→ 触发功能教学（AB 循环/倍速/音频延迟/倒计时/帧标记）
                    if (!onboarding.moreMenuTutorialDone) moreTutorialIndex = 0
                },
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
                        // 退出前显式落盘本次练习配置（音频延迟等，FR：退出练习保留、按视频恢复），ON_STOP 双保险
                        playerViewModel.saveHistory()
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

        // 教学 Overlay（置顶）：播放页教学 → 完成后标记；更多功能教学 → 完成后标记
        if (playerTutorialIndex >= 0) {
            TutorialOverlay(
                steps = PLAYER_TUTORIAL_STEPS,
                stepIndex = playerTutorialIndex,
                bounds = tutorialBounds,
                fallbackTargets = mapOf(EXIT_HINT_KEY to LEFT_TOP_EXIT_AREA),
                modifier = Modifier.zIndex(20f),
                onAdvance = {
                    if (playerTutorialIndex >= PLAYER_TUTORIAL_STEPS.lastIndex) {
                        playerTutorialIndex = -1
                        playerViewModel.markPlayerTutorialDone()
                    } else {
                        playerTutorialIndex++
                    }
                },
            )
        } else if (moreTutorialIndex >= 0) {
            TutorialOverlay(
                steps = MORE_TUTORIAL_STEPS,
                stepIndex = moreTutorialIndex,
                bounds = tutorialBounds,
                modifier = Modifier.zIndex(20f),
                onAdvance = {
                    if (moreTutorialIndex >= MORE_TUTORIAL_STEPS.lastIndex) {
                        moreTutorialIndex = -1
                        playerViewModel.markMoreMenuTutorialDone()
                    } else {
                        moreTutorialIndex++
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

// ==== 教学步骤（FR：首次使用引导） ====

/** 沉浸退出按钮仅存在于播放态；控制态教学用屏幕左上角示意区域代替。 */
private const val EXIT_HINT_KEY = "exitHint"
private val LEFT_TOP_EXIT_AREA = Rect(0f, 0f, 144f, 144f)

/** 播放页教学（导入首个视频后首次进入）：进度条 / 步进 / 播放 / 退出 / 更多功能。 */
private val PLAYER_TUTORIAL_STEPS = listOf(
    TutorialStep(
        title = "拖动进度条",
        body = "点击或拖动进度条任意位置即可快速定位；暂停画面会逐帧预览，方便找帧。",
        targetKey = "progressBar",
    ),
    TutorialStep(
        title = "逐帧步进",
        body = "点击 −0.1s / +0.1s 步进按钮，可精确对齐当前帧，逐帧拆解动作细节。",
        targetKey = "stepButton",
    ),
    TutorialStep(
        title = "播放练习",
        body = "点击播放按钮从当前帧开始练习，随时可暂停回到本页面继续调整。",
        targetKey = "playButton",
    ),
    TutorialStep(
        title = "退出练习",
        body = "沉浸练习中，双击左上角 × 按钮、或连续两次点按屏幕任意处，即可退出练习。",
        targetKey = EXIT_HINT_KEY,
    ),
    TutorialStep(
        title = "更多功能",
        body = "点击右侧「更多功能」按钮，展开 AB 循环、倍速、音频延迟、倒计时、帧标记等高级功能。",
        targetKey = "moreButton",
    ),
)

/** 更多功能教学（首次展开）：AB 循环 / 倍速 / 音频延迟 / 倒计时 / 帧标记。 */
private val MORE_TUTORIAL_STEPS = listOf(
    TutorialStep(
        title = "AB 点循环",
        body = "点击「AB 设点」设置区间起点与终点，可反复练习难点段落；拖动进度条上的 A/B 标记可微调区间。",
        targetKey = "loopButton",
    ),
    TutorialStep(
        title = "倍速调整",
        body = "选择 0.5x–2.0x 六档倍速练习，变速不改变音高，适合由慢到快逐步提速。",
        targetKey = "speedButton",
    ),
    TutorialStep(
        title = "音频延迟",
        body = "使用 ±5ms 步进微调音频延迟（±200ms），解决蓝牙耳机或外接音箱的音画不同步；退出练习后自动保留。",
        targetKey = "audioDelay",
    ),
    TutorialStep(
        title = "倒计时预听",
        body = "设置 0 / 3 / 5 秒倒计时，播放前预留准备时间；选择 5s 会完整预听 5 秒。",
        targetKey = "countdownButton",
    ),
    TutorialStep(
        title = "帧标记",
        body = "点击 + 为当前帧添加标记（可附文字提示），练习时在对应位置显示提醒，点击列表可随时跳转。",
        targetKey = "markAddButton",
    ),
)