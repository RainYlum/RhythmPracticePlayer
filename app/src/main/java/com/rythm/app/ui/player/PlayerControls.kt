package com.rythm.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.rythm.app.model.FrameMark
import com.rythm.app.model.PlaybackSpeed
import com.rythm.app.model.PlayerPhase
import com.rythm.app.model.PlaybackUiState
import com.rythm.app.ui.common.formatPlayTime

/**
 * CONTROL 控制面板（详细设计 3.4.2）：完整暂停控制窗。
 * 面板占屏幕下 30%（内容超出时内部滚动），背景 50% 透明露出视频画面；
 * 倍速/倒计时/音频延迟均为"总按钮 + 向上弹出菜单"（I4：无滑块组件），
 * AB 微调按钮仅 ±0.1s（拖动 A/B 端点后显示，调整后当前帧跟随）。
 */
@Composable
fun PlayerControlsPanel(
    state: PlaybackUiState,
    nowMs: Long,
    displayName: String,
    marks: List<FrameMark>,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: (Long) -> Unit,
    onTapLoopBand: () -> Unit,
    onStepBack: (Long) -> Unit,
    onStepFwd: (Long) -> Unit,
    onSpeed: (PlaybackSpeed) -> Unit,
    onQuickLoop: () -> Unit,
    onClearSelection: () -> Unit,
    onNudgeA: (Long) -> Unit,
    onNudgeB: (Long) -> Unit,
    onNudgeDragA: (Long) -> Unit,
    onNudgeDragB: (Long) -> Unit,
    onToggleContinuousLoop: () -> Unit,
    onCountdownSec: (Int) -> Unit,
    onToggleShowMillis: () -> Unit,
    onAudioOffset: (Int) -> Unit,
    onAddMark: (String) -> Unit,
    onOpenBookmarks: () -> Unit,
    onExit: () -> Unit,
) {
    // 外推位置（AC-02.3）：暂停/ENDED 时引擎确认值直接返回；按时长钳制防过冲
    val positionMs = state.extrapolatedDisplayPositionMs(nowMs)
        .coerceIn(0L, state.durationMs.coerceAtLeast(0L))
    var showAddMarkDialog by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var countdownMenuOpen by remember { mutableStateOf(false) }
    var audioDelayMenuOpen by remember { mutableStateOf(false) }
    // AB 点微调显隐：仅最近拖动过 A/B 端点时显示，其它操作（进度/播放/设点/清除）自动隐藏
    var nudgeTarget by remember { mutableStateOf<AbDragTarget?>(null) }
    fun hideNudge() { nudgeTarget = null }

    // AB 一键按钮状态文案（未开启 / A 仅设 / A·B 区间）
    val abLabel = when {
        state.loopStartMs == null -> "AB 设点"
        state.loopEndMs == null -> "A ${formatPlayTime(state.loopStartMs, state.showMillis)}"
        else -> "A ${formatPlayTime(state.loopStartMs, state.showMillis)} · B ${formatPlayTime(state.loopEndMs, state.showMillis)}"
    }
    // DropdownMenu 向上弹出位移：菜单高 = 每项 48dp + 容器上下边距 16dp
    val speedMenuUpOffset = DpOffset(0.dp, -(48.dp * PlaybackSpeed.entries.size + 16.dp))
    val countdownMenuUpOffset = DpOffset(0.dp, -(48.dp * CDN_MENU_ITEMS.size + 16.dp))
    val audioDelayMenuUpOffset = DpOffset(0.dp, -(48.dp * AUDIO_DELAY_MENU_ITEMS.size + 16.dp))
    // 控制面板最大高度（屏幕 30%）+ 50% 透明背景：面板内可见视频画面
    val panelMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.30f

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .heightIn(max = panelMaxHeight)
                .background(Color(0x80000000))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        // 顶部栏：文件名 + 毫秒开关 + 退出
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("毫秒", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Switch(
                checked = state.showMillis,
                onCheckedChange = { onToggleShowMillis() },
                modifier = Modifier.scale(0.7f),
            )
            IconButton(onClick = onExit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "退出播放", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        // 非致命错误条（选区校验等，详细设计 §8：错误折叠进 state 统一渲染）
        state.error?.let { err ->
            if (state.phase != PlayerPhase.ERROR) {
                Text(
                    text = err.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x66B00020))
                        .padding(8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 时间行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatPlayTime(positionMs, state.showMillis),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                text = " / ${formatPlayTime(state.durationMs, state.showMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }

        // 进度条（拖动 scrub 节流 80ms 高频通道 / 松手精确定位；色带点击跳 A）
        TimelineScrubber(
            positionMs = positionMs,
            bufferedMs = state.bufferedPositionMs,
            durationMs = state.durationMs,
            loopStartMs = state.loopStartMs,
            loopEndMs = state.loopEndMs,
            marks = marks,
            onScrub = onScrub,
            onCommit = onSeek,
            onTapLoopBand = onTapLoopBand,
            onNudgeA = onNudgeDragA,
            onNudgeB = onNudgeDragB,
            onAbDragStarted = { nudgeTarget = it },
        )
        Spacer(Modifier.height(4.dp))

        // 步进 + 播放
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StepButton("−1s") { onStepBack(1_000L) }
            StepButton("−0.1s") { onStepBack(100L) }
            IconButton(
                onClick = { hideNudge(); if (state.isPlaying) onPause() else onPlay() },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = if (state.phase == PlayerPhase.ENDED) Icons.Filled.Replay else {
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
                    },
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
            StepButton("+0.1s") { onStepFwd(100L) }
            StepButton("+1s") { onStepFwd(1_000L) }
        }
        Spacer(Modifier.height(4.dp))

        // 快捷行（进度条右下）：AB 一键设点（倍速左侧）+ 连续循环/清除 + 倍速菜单（右端）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.loopStartMs != null,
                onClick = { hideNudge(); onQuickLoop() },
                label = { Text(abLabel, color = Color.White, style = MaterialTheme.typography.labelMedium) },
            )
            Spacer(Modifier.width(8.dp))
            Text("连续循环", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Switch(
                checked = state.continuousLoop,
                onCheckedChange = { hideNudge(); onToggleContinuousLoop() },
                enabled = state.loopEnabled,
                modifier = Modifier.scale(0.7f),
            )
            TextButton(onClick = { hideNudge(); onClearSelection() }, enabled = state.loopEnabled) {
                Text(
                    text = "清除",
                    color = if (state.loopEnabled) Color.White else Color.White.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            // 倍速按钮：显示当前档（默认 1.0x），点击向上弹出六档菜单（FR-03.1，I4 无滑块）
            Box {
                TextButton(
                    onClick = { speedMenuOpen = true },
                    modifier = Modifier.testTag("speedButton"),
                ) {
                    Text(
                        text = "${state.playbackSpeed.label} ▾",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                DropdownMenu(
                    expanded = speedMenuOpen,
                    onDismissRequest = { speedMenuOpen = false },
                    offset = speedMenuUpOffset,
                ) {
                    PlaybackSpeed.entries.forEach { speed ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = speed.label,
                                    color = if (speed == state.playbackSpeed) MaterialTheme.colorScheme.primary else Color.White,
                                )
                            },
                            trailingIcon = {
                                if (speed == state.playbackSpeed) {
                                    Icon(Icons.Filled.Check, contentDescription = "当前倍速", tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            onClick = { onSpeed(speed); speedMenuOpen = false },
                        )
                    }
                }
            }
        }
        // AB 点微调（仅最近拖动过 A/B 端点时显示）：只保留 ±0.1s，调整后当前帧跟随 A/B 点
        if (nudgeTarget != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (nudgeTarget == AbDragTarget.A) {
                    Text("A 微调", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Row {
                        listOf(-100L, 100L).forEach { step ->
                            TextButton(
                                onClick = { onNudgeA(step) },
                                enabled = (state.loopStartMs ?: 0L) + step >= 0L,
                            ) {
                                Text(if (step < 0) "−0.1s" else "+0.1s", color = Color.White, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                } else {
                    Text("B 微调", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Row {
                        listOf(-100L, 100L).forEach { step ->
                            TextButton(
                                onClick = { onNudgeB(step) },
                                enabled = (state.loopEndMs ?: 0L) + step <= state.durationMs,
                            ) {
                                Text(if (step < 0) "−0.1s" else "+0.1s", color = Color.White, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        // 总按钮 + 向上菜单：倒计时 / 音频延迟（与倍速同交互模式），右侧帧标记入口
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                FilterChip(
                    selected = false,
                    onClick = { countdownMenuOpen = true },
                    label = { Text("倒计时 ${state.countdownSec}s", color = Color.White, style = MaterialTheme.typography.labelMedium) },
                )
                DropdownMenu(
                    expanded = countdownMenuOpen,
                    onDismissRequest = { countdownMenuOpen = false },
                    offset = countdownMenuUpOffset,
                ) {
                    CDN_MENU_ITEMS.forEach { sec ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${sec}s",
                                    color = if (sec == state.countdownSec) MaterialTheme.colorScheme.primary else Color.White,
                                )
                            },
                            trailingIcon = {
                                if (sec == state.countdownSec) {
                                    Icon(Icons.Filled.Check, contentDescription = "当前倒计时", tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            onClick = { onCountdownSec(sec); countdownMenuOpen = false },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                FilterChip(
                    selected = false,
                    onClick = { audioDelayMenuOpen = true },
                    label = { Text("音频延迟 ${formatAudioOffset(state.audioOffsetMs)}", color = Color.White, style = MaterialTheme.typography.labelMedium) },
                )
                DropdownMenu(
                    expanded = audioDelayMenuOpen,
                    onDismissRequest = { audioDelayMenuOpen = false },
                    offset = audioDelayMenuUpOffset,
                ) {
                    AUDIO_DELAY_MENU_ITEMS.forEach { off ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = formatAudioOffset(off),
                                    color = if (off == state.audioOffsetMs) MaterialTheme.colorScheme.primary else Color.White,
                                )
                            },
                            trailingIcon = {
                                if (off == state.audioOffsetMs) {
                                    Icon(Icons.Filled.Check, contentDescription = "当前延迟", tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            onClick = { onAudioOffset(off); audioDelayMenuOpen = false },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showAddMarkDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "添加帧标记", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onOpenBookmarks, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Bookmarks, contentDescription = "帧标记列表", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        }

        // 添加帧标记文本输入（FR-06：可空文本 = 纯跳转书签，不产生字幕）
        if (showAddMarkDialog) {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddMarkDialog = false },
                title = { Text("添加帧标记") },
                text = {
                    Column {
                        Text(
                            "当前位置：${formatPlayTime(positionMs, state.showMillis)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("标记文本（可留空）") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onAddMark(text.trim())
                        showAddMarkDialog = false
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddMarkDialog = false }) { Text("取消") }
                },
            )
        }
    }
}

/** 倒计时可选项（FR-10.1）：0 关闭 / 3s / 5s。 */
private val CDN_MENU_ITEMS = listOf(0, 3, 5)

/** 音频延迟菜单档位（FR-08）：±100ms 区间常用档，按钮显示当前值。 */
private val AUDIO_DELAY_MENU_ITEMS = listOf(-100, -50, -10, 0, 10, 50, 100)

/** 音频延迟按钮/菜单文案（FR-08）：0=同步，正=音频延后，负=音频提前。 */
private fun formatAudioOffset(offsetMs: Int): String = when {
    offsetMs == 0 -> "0ms"
    offsetMs > 0 -> "+${offsetMs}ms"
    else -> "${offsetMs}ms"
}

/** 面板内小步进按钮（±0.1s/±1s，FR-05.2）。 */
@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}