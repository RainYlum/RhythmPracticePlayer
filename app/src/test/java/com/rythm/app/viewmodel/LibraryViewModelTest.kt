package com.rythm.app.viewmodel

import android.net.Uri
import com.rythm.app.data.repository.VideoLibraryRepository
import com.rythm.app.data.repository.VideoRepository
import com.rythm.app.model.LibraryItem
import com.rythm.app.model.VideoMetadata
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

/**
 * LibraryViewModel 测试（架构 §12）：导入委派（docId 归一）、列表映射、备注/删除/打开委派。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private companion object {
        const val URI = "content://media/external/video/media/7"
    }

    private val uri: Uri = mock(Uri::class.java).also {
        Mockito.`when`(it.toString()).thenReturn(URI)
        Mockito.`when`(it.scheme).thenReturn("content")
    }

    private lateinit var libraryRepo: FakeLibraryRepository
    private lateinit var videoRepo: FakeVideoRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        libraryRepo = FakeLibraryRepository()
        videoRepo = FakeVideoRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createVm() = LibraryViewModel(libraryRepo, videoRepo)

    @Test
    fun `importVideo 委派授权持久化 docId 去重登记`() = runTest {
        videoRepo.docId = "doc-7"

        createVm().importVideo(uri, "练习A")
        advanceUntilIdle()

        assertEquals(listOf(uri), videoRepo.permissionUris)
        assertEquals(listOf(Triple(URI, "练习A", "doc-7")), libraryRepo.addCalls)
    }

    @Test
    fun `importVideo 无 docId 时退回 uri 字符串`() = runTest {
        videoRepo.docId = null

        createVm().importVideo(uri, "练习B")
        advanceUntilIdle()

        assertEquals(listOf(Triple(URI, "练习B", URI)), libraryRepo.addCalls)
    }

    @Test
    fun `importVideo 失败置 error`() = runTest {
        libraryRepo.addError = IllegalStateException("datastore busy")

        val vm = createVm()
        // WhileSubscribed：先建立订阅让 combine 活跃（否则 uiState 冻结在初始值）
        val job = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.importVideo(uri, "练习C")
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        assertEquals(true, vm.uiState.value.error?.contains("导入失败"))
        job.cancel()
    }

    @Test
    fun `列表数据源映射到 uiState`() = runTest {
        val vm = createVm()
        libraryRepo.items.value = listOf(
            LibraryItem(uri = "u1", docId = "d1", displayName = "A", addedAt = 2L),
            LibraryItem(uri = "u2", docId = "d2", displayName = "B", addedAt = 1L),
        )
        val job = launch { vm.uiState.collect {} } // WhileSubscribed 需建立订阅
        advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(2, s.items.size)
        assertEquals("u1", s.items.first().uri)
        assertFalse(s.isLoading) // map 后不再携带 loading 标记

        libraryRepo.items.value = listOf(LibraryItem(uri = "u3", docId = "d3", displayName = "C", addedAt = 3L))
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.items.size)
        assertEquals("u3", vm.uiState.value.items.single().uri)

        job.cancel()
    }

    @Test
    fun `isPlayable 元数据与流可达都通过才可播放`() = runTest {
        val vm = createVm()
        videoRepo.metadata = VideoMetadata(durationMs = 60_000, frameRate = null, rotationDeg = 0)
        assertTrue(vm.isPlayable(uri))

        videoRepo.metadata = null // 元数据读失败（不可解码）
        assertFalse(vm.isPlayable(uri))
    }

    @Test
    fun `updateNote deleteVideo markOpened 委派方向正确`() = runTest {
        val vm = createVm()

        vm.updateNote(URI, "重点练习")
        vm.updateNote(URI, null)
        vm.deleteVideo(URI)
        vm.markOpened(URI)
        advanceUntilIdle()

        assertEquals(listOf(URI to "重点练习", URI to null), libraryRepo.notes)
        assertEquals(listOf(URI), libraryRepo.deletedUris) // 级联入口在 repository 层（I6）
        assertEquals(1, libraryRepo.lastOpened.size)
        assertEquals(URI, libraryRepo.lastOpened.single().first)
    }

    // ==== Fakes ====

    private class FakeLibraryRepository : VideoLibraryRepository {
        val items = MutableStateFlow<List<LibraryItem>>(emptyList())
        val addCalls = mutableListOf<Triple<String, String, String>>()
        val notes = mutableListOf<Pair<String, String?>>()
        val deletedUris = mutableListOf<String>()
        val lastOpened = mutableListOf<Pair<String, Long>>()
        var addError: Exception? = null

        override fun listFlow(): Flow<List<LibraryItem>> = items
        override suspend fun list(): List<LibraryItem> = items.value
        override suspend fun getItem(uri: String): LibraryItem? = items.value.firstOrNull { it.uri == uri }

        override suspend fun add(uri: String, displayName: String, docId: String): Boolean {
            addCalls += Triple(uri, displayName, docId)
            addError?.let { throw it }
            return true
        }

        override suspend fun updateNote(uri: String, note: String?) {
            notes += uri to note
        }

        override suspend fun updateLastOpened(uri: String, at: Long) {
            lastOpened += uri to at
        }

        override suspend fun updateThumb(uri: String, thumbPath: String) = Unit
        override suspend fun deleteVideo(uri: String) {
            deletedUris += uri
        }
    }

    private class FakeVideoRepository : VideoRepository {
        var docId: String? = "doc-1"
        var metadata: VideoMetadata? = null
        val permissionUris = mutableListOf<Uri>()

        override fun takePersistablePermission(uri: Uri) {
            permissionUris += uri
        }

        override suspend fun readMetadata(uri: Uri): VideoMetadata =
            metadata ?: error("metadata unavailable")

        override suspend fun isUriReadable(uri: Uri) = true
        override fun resolveDocId(uri: Uri): String? = docId
    }
}