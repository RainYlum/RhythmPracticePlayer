package com.rythm.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rythm.app.data.repository.PreferenceRepository
import com.rythm.app.data.repository.VideoLibraryRepository
import com.rythm.app.data.repository.VideoRepository
import com.rythm.app.data.store.ThumbnailStore
import com.rythm.app.model.LibraryItem
import com.rythm.app.model.OnboardingStatus
import com.rythm.app.player.FrameGrabber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** 视频库主页 UI 状态（FR-11，架构 5.2 LibraryViewModel）。 */
data class LibraryUiState(
    val items: List<LibraryItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * 视频库主页 ViewModel（FR-11，架构 5.2）：
 * 列表数据源（addedAt 倒序）、导入登记（docId 去重 + 列表缩略图生成）、
 * 旧库缩略图补全（thumbPath 缺失/文件失效时自动重新生成）、备注编辑、删除（级联清理，I6）。
 * 打开条目后的导航由 UI 层完成（跳播放页）。
 */
class LibraryViewModel(
    private val libraryRepository: VideoLibraryRepository,
    private val videoRepository: VideoRepository,
    private val thumbStore: ThumbnailStore,
    private val frameGrabber: FrameGrabber,
    private val preferenceRepository: PreferenceRepository,
) : ViewModel() {

    /** 教学引导状态：首次使用简介 + 引导导入第一个视频（「再次教学」重置后重新触发）。 */
    val onboarding: StateFlow<OnboardingStatus> = preferenceRepository.onboarding
        .stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingStatus())

    init {
        // 旧库兼容（R-12）：列表出现后为缺失/失效缩略图的条目补生成，失败静默等待下次变化重试
        viewModelScope.launch {
            libraryRepository.listFlow().collect { items ->
                items.forEach { item ->
                    if (item.thumbPath.isNullOrEmpty() || !File(item.thumbPath).exists()) {
                        val parsed = runCatching { Uri.parse(item.uri) }.getOrNull() ?: return@forEach
                        ensureThumb(parsed, item.docId)?.let { path ->
                            runCatching { libraryRepository.updateThumb(item.uri, path) }
                        }
                    }
                }
            }
        }
    }

    private val _uiState = MutableStateFlow(LibraryUiState(isLoading = true))

    /** 列表 + 本地状态合并（单一事实源）：导入失败等置 [_uiState].error，随列表刷新同帧呈现。 */
    val uiState: StateFlow<LibraryUiState> = combine(_uiState, libraryRepository.listFlow()) { state, items ->
        state.copy(items = items, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _uiState.value)

    /**
     * 可播放预检（FR-01.2 AC-01.3）：元数据可读（含 duration）+ 流可达才算可播放。
     * 失败由 UI 提示"无法播放该视频"且不入库。
     */
    suspend fun isPlayable(uri: Uri): Boolean = runCatching {
        videoRepository.readMetadata(uri)
        videoRepository.isUriReadable(uri)
    }.getOrDefault(false)

    /**
     * 导入登记（FR-01.5/11.5）：SAF 授权持久化 + docId 去重 + 列表缩略图生成。
     * 是否新增为异步结果（DataStore 事务内判断），由列表数据流刷新自然呈现；失败置 uiState.error。
     */
    fun importVideo(uri: Uri, displayName: String) {
        videoRepository.takePersistablePermission(uri)
        val docId = videoRepository.resolveDocId(uri) ?: uri.toString()
        viewModelScope.launch {
            runCatching { libraryRepository.add(uri.toString(), displayName, docId) }
                .onFailure { _uiState.value = _uiState.value.copy(error = "导入失败：${it.message}") }
                .onSuccess {
                    // 列表缩略图（R-12）：取视频前 1/3 时长帧；失败静默降级为占位块，不影响入库
                    ensureThumb(uri, docId)?.let { path ->
                        runCatching { libraryRepository.updateThumb(uri.toString(), path) }
                    }
                }
        }
    }

    /** 编辑备注（可空 = 清除备注）。 */
    fun updateNote(uri: String, note: String?) {
        viewModelScope.launch { libraryRepository.updateNote(uri, note) }
    }

    /** 删除视频（FR-11.4）：级联清除历史/书签/缩略图，不删源文件（I6）。 */
    fun deleteVideo(uri: String) {
        viewModelScope.launch { libraryRepository.deleteVideo(uri) }
    }

    /** 打开条目：刷新 lastOpenedAt（供排序/最近打开）。 */
    fun markOpened(uri: String) {
        viewModelScope.launch { libraryRepository.updateLastOpened(uri, System.currentTimeMillis()) }
    }

    /** 首次引导教学完成标记（简介 + 引导导入第一个视频）。 */
    fun markIntroDone() {
        viewModelScope.launch { preferenceRepository.setIntroDone(true) }
    }

    /** 重置全部教学标记（视频库「再次教学」确认后调用，用户可重新体验完整教学流程）。 */
    fun resetOnboarding() {
        viewModelScope.launch { preferenceRepository.resetOnboarding() }
    }

    /**
     * 提取一帧并保存至 thumbnails/<docKey>.jpg（≤640px 宽，R-12），返回绝对路径；失败返回 null。
     * 取视频前 1/3 时长帧作预览，避开开头黑帧/结尾黑帧。
     */
    private suspend fun ensureThumb(uri: Uri, docId: String): String? {
        val durationMs = runCatching { videoRepository.readMetadata(uri) }.getOrNull()?.durationMs ?: 0L
        if (durationMs <= 0L) return null
        val bitmap = frameGrabber.grabFrame(uri, durationMs / 3, LibraryThumbMaxWidthPx) ?: return null
        val file = thumbStore.newThumbFile(docId)
        return if (thumbStore.saveJpeg(bitmap, file, LibraryThumbMaxWidthPx)) file.absolutePath else null
    }

    companion object {
        /** 视频库列表缩略图宽上限（R-12，ThumbnailStore 默认）。 */
        private const val LibraryThumbMaxWidthPx = 640
    }
}