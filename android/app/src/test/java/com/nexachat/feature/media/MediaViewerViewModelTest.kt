package com.nexachat.feature.media

import androidx.lifecycle.SavedStateHandle
import com.nexachat.MainDispatcherRule
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.data.AttachmentRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaViewerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val attachmentRepository: AttachmentRepository = mockk()

    private companion object {
        const val ATTACHMENT_ID = "att-1"
    }

    /** Builds the VM with the same nav-arg keys AppNavHost writes. */
    private fun createViewModel(
        name: String? = "clip.mp4",
        mime: String? = "video/mp4",
    ): MediaViewerViewModel = MediaViewerViewModel(
        savedStateHandle = SavedStateHandle(
            buildMap {
                put("attachmentId", ATTACHMENT_ID)
                name?.let { put("name", it) }
                mime?.let { put("mime", it) }
            },
        ),
        attachmentRepository = attachmentRepository,
    )

    @Test
    fun `image mime skips the download hop — Coil streams it`() = runTest {
        val viewModel = createViewModel(name = "photo.jpg", mime = "image/jpeg")

        val state = viewModel.uiState.value
        assertTrue(state.isImage)
        assertFalse(state.loading)
        assertEquals("photo.jpg", state.displayName)
        coVerify(exactly = 0) {
            attachmentRepository.downloadToCache(any(), any())
        }
    }

    @Test
    fun `non-image downloads to cache on init and exposes the file`() = runTest {
        val cached = File("cached-clip.mp4")
        coEvery { attachmentRepository.downloadToCache(ATTACHMENT_ID, "clip.mp4") } returns
            Result.Success(cached)

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertFalse(state.isImage)
        assertFalse(state.loading)
        assertEquals(cached, state.file)
        assertNull(state.error)
        coVerify(exactly = 1) {
            attachmentRepository.downloadToCache(ATTACHMENT_ID, "clip.mp4")
        }
    }

    @Test
    fun `download failure surfaces the error and reload retries`() = runTest {
        coEvery { attachmentRepository.downloadToCache(any(), any()) } returns
            Result.Failure(ApiError.Network("down"))

        val viewModel = createViewModel()
        assertEquals(ApiError.Network("down"), viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)

        val cached = File("cached-clip.mp4")
        coEvery { attachmentRepository.downloadToCache(any(), any()) } returns
            Result.Success(cached)
        viewModel.reload()

        val state = viewModel.uiState.value
        assertEquals(cached, state.file)
        assertNull(state.error)
        coVerify(exactly = 2) {
            attachmentRepository.downloadToCache(ATTACHMENT_ID, "clip.mp4")
        }
    }

    @Test
    fun `reload while a download is in flight is a no-op`() = runTest {
        // Never completes — keeps loading=true for the whole test.
        coEvery { attachmentRepository.downloadToCache(any(), any()) } coAnswers {
            suspendCancellableCoroutine<Result<File>> { }
        }

        val viewModel = createViewModel()
        assertTrue(viewModel.uiState.value.loading)

        viewModel.reload()

        coVerify(exactly = 1) {
            attachmentRepository.downloadToCache(any(), any())
        }
    }

    @Test
    fun `reload on an image is a no-op — there is no download to retry`() = runTest {
        val viewModel = createViewModel(name = "photo.png", mime = "image/png")

        viewModel.reload()

        coVerify(exactly = 0) {
            attachmentRepository.downloadToCache(any(), any())
        }
    }

    @Test
    fun `blank nav args degrade to nulls — the UI falls back to Attachment`() = runTest {
        coEvery { attachmentRepository.downloadToCache(any(), any()) } returns
            Result.Failure(ApiError.Network("offline"))

        val viewModel = createViewModel(name = "  ", mime = "  ")

        val state = viewModel.uiState.value
        assertNull(state.displayName)
        assertNull(state.mimeType)
        // Blank mime is not an image → the download path runs.
        assertFalse(state.isImage)
    }
}
