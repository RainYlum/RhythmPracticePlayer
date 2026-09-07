package com.rythm.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rythm.app.model.FrameMark
import com.rythm.app.ui.common.LocalThumb
import com.rythm.app.ui.common.formatPlayTime

/**
 * 帧标记面板（FR-06，详细设计 3.4.2/5.6）：列表（缩略图 + 时间 + 文本）、行点击跳转
 * （seekTo 定格，AC-06.2）、编辑文本、删除、当前帧添加（文本可空 = 纯跳转书签，不产生字幕）。
 * 删除为低风险轻操作不弹确认（重建成本低，无源文件牵连）。
 */
@Composable
fun BookmarkPanel(
    marks: List<FrameMark>,
    onClose: () -> Unit,
    onJumpTo: (Long) -> Unit,
    onEditText: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    var editing: FrameMark? by remember { mutableStateOf(null) }
    var editText by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var addText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("帧标记（${marks.size}）") },
        text = {
            if (marks.isEmpty()) {
                Text(
                    "暂无标记。暂停后点「添加」在当前帧打点，可附文本作沉浸字幕提醒。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    marks.forEach { mark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpTo(mark.timeMs) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LocalThumb(mark.thumbPath, Modifier.size(44.dp), cornerRadius = 6.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = formatPlayTime(mark.timeMs),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                if (mark.text.isNotBlank()) {
                                    Text(
                                        text = mark.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(onClick = { editing = mark; editText = mark.text }) {
                                Icon(Icons.Filled.Edit, contentDescription = "编辑文本")
                            }
                            IconButton(onClick = { onDelete(mark.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除标记")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showAdd = true; addText = "" }) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        },
    )

    // 编辑标记文本（空文本 = 降级为纯跳转书签）
    val current = editing
    if (current != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑标记文本") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    label = { Text("文本（可留空）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEditText(current.id, editText.trim())
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }

    // 在当前帧添加标记（PlayerControls 面板 + 入口健在）
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("在当前帧添加标记") },
            text = {
                OutlinedTextField(
                    value = addText,
                    onValueChange = { addText = it },
                    label = { Text("标记文本（可留空）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAdd(addText.trim())
                    showAdd = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } },
        )
    }
}