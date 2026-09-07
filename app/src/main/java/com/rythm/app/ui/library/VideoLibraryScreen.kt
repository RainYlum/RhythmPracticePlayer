package com.rythm.app.ui.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rythm.app.model.LibraryItem
import com.rythm.app.ui.common.LocalThumb
import com.rythm.app.viewmodel.LibraryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 视频库主页（FR-11）：列表（缩略图/名称备注/最近打开）+ 空态引导 + 导入流程
 * （OpenDocument → 可解码预检 AC-01.3 → 入库去重 → 首次备注 Dialog → 打开播放页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoLibraryScreen(
    viewModel: LibraryViewModel,
    onOpenVideo: (uri: String, displayName: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 导入待办（uri, displayName）：预检通过 → 弹首次备注 Dialog
    var pendingImport by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var deletingItem by remember { mutableStateOf<LibraryItem?>(null) }
    var editingItem by remember { mutableStateOf<LibraryItem?>(null) }

    // 错误提示（如导入失败；之后列表刷新自然呈现）
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "未命名视频"
            scope.launch {
                if (viewModel.isPlayable(uri)) {
                    pendingImport = uri to name
                } else {
                    Toast.makeText(context, "无法播放该视频", Toast.LENGTH_SHORT).show() // AC-01.3 不入库
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("练习视频库") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { openDocument.launch(arrayOf("video/*")) }) {
                Icon(Icons.Filled.Add, contentDescription = "导入视频")
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (uiState.isLoading) {
                // 首帧数据未就绪：短暂空白后列表出现（DataStore 首读为 IO）
            } else if (uiState.items.isEmpty()) {
                EmptyLibrary(onImport = { openDocument.launch(arrayOf("video/*")) })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.items, key = { it.uri }) { item ->
                        LibraryRow(
                            item = item,
                            onOpen = {
                                viewModel.markOpened(item.uri)
                                onOpenVideo(item.uri, item.displayName)
                            },
                            onEditNote = { editingItem = item },
                            onDelete = { deletingItem = item },
                        )
                    }
                }
            }
        }
    }

    // 首次备注 Dialog（FR-01.6）：留空 = 默认文件名；取消 = 放弃导入
    var noteText by remember { mutableStateOf("") }
    pendingImport?.let { (uri, name) ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("添加备注（可留空）") },
            text = {
                Column {
                    Text("视频：$name", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("备注") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importVideo(uri, name)
                    if (noteText.isNotBlank()) viewModel.updateNote(uri.toString(), noteText.trim())
                    onOpenVideo(uri.toString(), name)
                    noteText = ""
                    pendingImport = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    noteText = ""
                    pendingImport = null
                }) { Text("取消") }
            },
        )
    }

    // 删除确认（FR-11.4：级联清历史/书签/缩略图，不删源文件 —— I6）
    deletingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            title = { Text("删除视频") },
            text = { Text("确定从视频库删除「${item.note ?: item.displayName}」？\n将同时清除其播放历史、帧标记与缩略图（源文件不受影响）。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteVideo(item.uri)
                    deletingItem = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingItem = null }) { Text("取消") }
            },
        )
    }

    // 编辑备注（可留空 = 清除备注）
    var editNoteText by remember { mutableStateOf("") }
    LaunchedEffect(editingItem) { editNoteText = editingItem?.note ?: "" }
    editingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text("编辑备注") },
            text = {
                OutlinedTextField(
                    value = editNoteText,
                    onValueChange = { editNoteText = it },
                    label = { Text("备注（留空清除）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateNote(item.uri, editNoteText.trim().ifBlank { null })
                    editingItem = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.VideoLibrary,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("还没有练习视频", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "点击下方按钮导入本地视频\n支持区间循环、倍速、帧标记等练习功能",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport) { Text("导入视频") }
    }
}

@Composable
private fun LibraryRow(
    item: LibraryItem,
    onOpen: () -> Unit,
    onEditNote: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LocalThumb(
            path = item.thumbPath,
            modifier = Modifier.size(56.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = item.note ?: item.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.note != null) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.lastOpenedAt > 0L) {
                Text(
                    text = "最近打开 ${formatOpenedAt(item.lastOpenedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("编辑备注") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onEditNote()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** OpenDocument 结果的显示名查询（无列时退回 null）。 */
private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
        } else {
            null
        }
    }
}.getOrNull()

private fun formatOpenedAt(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))