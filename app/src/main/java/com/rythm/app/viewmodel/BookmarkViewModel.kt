package com.rythm.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rythm.app.data.repository.BookmarkRepository
import com.rythm.app.data.store.ThumbnailStore
import com.rythm.app.model.FrameMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 帧标记/书签面板 ViewModel（FR-06，架构 5.2）：
 * 列表数据源（书签按时间升序展示）、文本编辑、删除（联动缩略图清理，I6）。
 *
 * 依赖注入说明（保持播放器单实例约束）：帧画面抓取由播放页持有播放器完成，
 * 因此"新增标记"在 [PlayerViewModel.addFrameMark]；本 VM 通过 [capture] 函数
 * 支持编辑场景的缩略图重抓（UI 侧从播放器注入，单测可伪造）。
 */
class BookmarkViewModel(
    private val bookmarkRepository: BookmarkRepository,
    private val thumbStore: ThumbnailStore,
) : ViewModel() {

    private val _marks = MutableStateFlow<List<FrameMark>>(emptyList())
    val marks: StateFlow<List<FrameMark>> = _marks

    /** 绑定视频（进入播放页时调用；换视频时重新订阅数据源）。 */
    fun attach(uri: String) {
        if (_marks.value.isNotEmpty() && currentUri == uri) return
        currentUri = uri
        _marks.value = emptyList()
        marksJob?.cancel()
        marksJob = viewModelScope.launch {
            bookmarkRepository.listFlow(uri)
                .collect { list -> _marks.value = list.sortedBy { it.timeMs } }
        }
    }

    /** 编辑文本（FR-06.3）。 */
    fun updateText(markId: String, text: String) {
        val uri = currentUri ?: return
        val updated = _marks.value.firstOrNull { it.id == markId } ?: return
        viewModelScope.launch {
            bookmarkRepository.update(uri, updated.copy(text = text))
        }
    }

    /**
     * 编辑缩略图（FR-06.4）：从 [positionMs] 重抓帧存缩略图（≤320px，R-12）。
     * @param capture 帧抓取函数（UI 注入 playerManager.captureFrameAt；失败返回 null 保留旧图）。
     */
    fun refreshThumb(markId: String, positionMs: Long, capture: suspend (Long, Int) -> android.graphics.Bitmap?) {
        val uri = currentUri ?: return
        val mark = _marks.value.firstOrNull { it.id == markId } ?: return
        viewModelScope.launch {
            val file = thumbStore.newMarkFile(markId)
            val saved = capture(positionMs, MarkThumbMaxWidth)?.let {
                thumbStore.saveJpeg(it, file, MarkThumbMaxWidth)
            } == true
            if (saved) bookmarkRepository.update(uri, mark.copy(thumbPath = file.absolutePath))
        }
    }

    /** 删除标记（FR-06.5）：条目 + 缩略图文件联动清理。 */
    fun remove(markId: String) {
        val uri = currentUri ?: return
        viewModelScope.launch { bookmarkRepository.remove(uri, markId) }
    }

    /** 测试/工具用：新增（不走帧抓取，缩略图留空）。 */
    fun addMark(timeMs: Long, text: String) {
        val uri = currentUri ?: return
        viewModelScope.launch {
            bookmarkRepository.add(
                uri,
                FrameMark(
                    id = UUID.randomUUID().toString(),
                    timeMs = timeMs,
                    text = text,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        marksJob?.cancel()
    }

    private var currentUri: String? = null
    private var marksJob: kotlinx.coroutines.Job? = null

    companion object {
        /** 帧标记缩略图宽（R-12，与 PlayerViewModel.addFrameMark 一致）。 */
        const val MarkThumbMaxWidth = 320
    }
}