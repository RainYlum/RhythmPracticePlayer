package com.rythm.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import com.rythm.app.data.repository.BookmarkRepository
import com.rythm.app.data.store.ThumbnailStore
import com.rythm.app.model.FrameMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File

/**
 * BookmarkViewModel 测试（FR-06，详设 5.2）：attach 订阅排序、编辑/重抓缩略图/删除委派。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkViewModelTest {

    private companion object {
        const val URI = "content://media/external/video/media/1"
    }

    private lateinit var repo: FakeBookmarkRepository
    private lateinit var thumbStore: FakeThumbnailStore
    private lateinit var vm: BookmarkViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repo = FakeBookmarkRepository()
        thumbStore = FakeThumbnailStore()
        vm = BookmarkViewModel(repo, thumbStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `attach 订阅列表并按时间升序展示`() = runTest {
        repo.marks.value = listOf(
            FrameMark(id = "m1", timeMs = 20_000, text = "后"),
            FrameMark(id = "m2", timeMs = 5_000, text = "前"),
        )
        vm.attach(URI)
        advanceUntilIdle()

        assertEquals(listOf("m2", "m1"), vm.marks.value.map { it.id })
    }

    @Test
    fun `重复 attach 同视频不重建订阅`() = runTest {
        repo.marks.value = listOf(FrameMark(id = "m1", timeMs = 1_000, text = "a"))
        vm.attach(URI)
        advanceUntilIdle()
        vm.attach(URI) // 已绑定同 uri：直接复用当前列表
        advanceUntilIdle()

        assertEquals(1, repo.subscribeUris.size)
        assertEquals(listOf("m1"), vm.marks.value.map { it.id })
    }

    @Test
    fun `换视频后重新订阅并清空旧列表`() = runTest {
        repo.marks.value = listOf(FrameMark(id = "old", timeMs = 1_000, text = "a"))
        vm.attach(URI)
        advanceUntilIdle()

        repo.marks.value = listOf(FrameMark(id = "new", timeMs = 2_000, text = "b"))
        vm.attach("content://media/external/video/media/2")
        advanceUntilIdle()

        assertEquals(listOf(URI, "content://media/external/video/media/2"), repo.subscribeUris)
        assertEquals(listOf("new"), vm.marks.value.map { it.id })
    }

    @Test
    fun `updateText 委派持久化编辑`() = runTest {
        repo.marks.value = listOf(FrameMark(id = "m1", timeMs = 1_000, text = "旧文本"))
        vm.attach(URI)
        advanceUntilIdle()

        vm.updateText("m1", "新文本")
        advanceUntilIdle()

        val (updatedUri, mark) = repo.updated.single()
        assertEquals(URI, updatedUri)
        assertEquals("新文本", mark.text)
    }

    @Test
    fun `refreshThumb 重抓成功回写 thumbPath`() = runTest {
        val mark = FrameMark(id = "m1", timeMs = 3_000, text = "标记")
        repo.marks.value = listOf(mark)
        vm.attach(URI)
        advanceUntilIdle()
        thumbStore.markFile = File("C:/tmp/marks/m1.jpg")

        vm.refreshThumb("m1", 3_000) { _, _ -> mock(Bitmap::class.java) }
        advanceUntilIdle()

        assertEquals(File("C:/tmp/marks/m1.jpg").absolutePath, repo.updated.single().second.thumbPath)
        assertEquals(3_000.toLong(), repo.updated.single().second.timeMs)
    }

    @Test
    fun `refreshThumb 抓帧失败或保存失败时保留旧图`() = runTest {
        val mark = FrameMark(id = "m1", timeMs = 3_000, text = "标记", thumbPath = "C:/tmp/marks/old.jpg")
        repo.marks.value = listOf(mark)
        vm.attach(URI)
        advanceUntilIdle()
        thumbStore.saveResult = false // 压缩保存失败

        vm.refreshThumb("m1", 3_000) { _, _ -> mock(Bitmap::class.java) }
        advanceUntilIdle()
        assertTrue(repo.updated.isEmpty()) // 保存失败不落库

        vm.refreshThumb("m1", 3_000) { _, _ -> null } // 抓帧失败
        advanceUntilIdle()
        assertTrue(repo.updated.isEmpty())
    }

    @Test
    fun `remove 委派删除并保持列表刷新`() = runTest {
        repo.marks.value = listOf(FrameMark(id = "m1", timeMs = 1_000, text = "a"))
        vm.attach(URI)
        advanceUntilIdle()

        vm.remove("m1")
        advanceUntilIdle()

        assertEquals(listOf(URI to "m1"), repo.removed)
        assertEquals(1, repo.subscribeUris.size) // 列表数据源仍为原 Flow（删除后由下游 emit）
    }

    @Test
    fun `addMark 登记时间与文本缩略图留空`() = runTest {
        vm.attach(URI)
        advanceUntilIdle()

        vm.addMark(9_999, "换指法")
        advanceUntilIdle()

        val (addedUri, mark) = repo.added.single()
        assertEquals(URI, addedUri)
        assertEquals(9_999L, mark.timeMs)
        assertEquals("换指法", mark.text)
        assertEquals("", mark.thumbPath)
    }

    @Test
    fun `未 attach 时操作静默忽略`() = runTest {
        vm.updateText("m1", "x")
        vm.remove("m1")
        vm.addMark(1_000, "x")
        advanceUntilIdle()

        assertTrue(repo.updated.isEmpty())
        assertTrue(repo.removed.isEmpty())
        assertTrue(repo.added.isEmpty())
        assertTrue(vm.marks.value.isEmpty())
    }

    // ==== Fakes ====

    private class FakeBookmarkRepository : BookmarkRepository {
        val marks = MutableStateFlow<List<FrameMark>>(emptyList())
        val subscribeUris = mutableListOf<String>()
        val added = mutableListOf<Pair<String, FrameMark>>()
        val updated = mutableListOf<Pair<String, FrameMark>>()
        val removed = mutableListOf<Pair<String, String>>()

        override fun listFlow(uri: String): Flow<List<FrameMark>> {
            subscribeUris += uri
            return marks
        }

        override suspend fun list(uri: String): List<FrameMark> = marks.value

        override suspend fun add(uri: String, mark: FrameMark) {
            added += uri to mark
        }

        override suspend fun update(uri: String, mark: FrameMark) {
            updated += uri to mark
        }

        override suspend fun remove(uri: String, markId: String) {
            removed += uri to markId
        }

        override suspend fun removeAll(uri: String) = Unit
    }

    private class FakeThumbnailStore : ThumbnailStore(mock(Context::class.java)) {
        var markFile: File = File("C:/tmp/marks/x.jpg")
        var saveResult = true

        override fun newMarkFile(markId: String): File = markFile

        override suspend fun saveJpeg(bitmap: Bitmap, dest: File, maxWidthPx: Int): Boolean = saveResult
    }
}