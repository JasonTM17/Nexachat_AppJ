package com.nexachat.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.AttachmentApi
import com.nexachat.core.network.dto.AttachmentDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Upload/download transport coverage (phase-09): progress is strictly
 * non-decreasing and clamps at 1f, the 25 MB guard fails typed before the
 * wire, HTTP errors map to [ApiError], and the dto lands intact.
 *
 * The resolver side is mocked Context/ContentResolver (JVM tests have no
 * Android runtime); the API side is MockK `Response`s like the other
 * repository tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentRepositoryTest {

    private val attachmentApi: AttachmentApi = mockk()
    private val context: Context = mockk()
    private val resolver: ContentResolver = mockk()
    private val uri: Uri = mockk()

    private lateinit var repository: AttachmentRepository

    private val dto = AttachmentDto("att-1", "pic.png", "image/png", 4L)

    private fun stubResolver(
        bytes: ByteArray,
        mime: String? = "image/png",
        size: Long = -1L,
    ) {
        every { context.contentResolver } returns resolver
        every { resolver.query(uri, null, null, null, null) } returns null
        if (size >= 0) {
            val pfd: ParcelFileDescriptor = mockk()
            every { pfd.statSize } returns size
            every { pfd.close() } just Runs
            every { resolver.openFileDescriptor(uri, "r") } returns pfd
        } else {
            every { resolver.openFileDescriptor(uri, "r") } returns null
        }
        every { resolver.getType(uri) } returns mime
        every { resolver.openInputStream(uri) } returns bytes.inputStream()
        every { uri.lastPathSegment } returns "pic.png"
    }

    @Before
    fun setUp() {
        repository = AttachmentRepository(attachmentApi, context)
    }

    @Test
    fun `upload emits monotonic progress ending at 1f then the dto`() = runTest {
        val bytes = "0123456789".toByteArray()
        stubResolver(bytes, size = bytes.size.toLong())
        coEvery { attachmentApi.upload(any()) } coAnswers {
            // Drive the real ProgressRequestBody — write the captured part
            // into a buffer so intermediate fractions actually fire.
            firstArg<MultipartBody.Part>().body.writeTo(Buffer())
            Response.success(dto)
        }

        val events = repository.upload(uri).toList()

        val progress = events.filterIsInstance<AttachmentUploadEvent.Progress>()
        assertTrue(progress.isNotEmpty())
        assertEquals(0f, progress.first().fraction, 0.0001f)
        assertEquals(1f, progress.last().fraction, 0.0001f)
        assertTrue(
            progress.zipWithNext().all { (a, b) -> b.fraction >= a.fraction },
        )
        assertEquals(AttachmentUploadEvent.Uploaded(dto), events.last())
    }

    @Test
    fun `upload reads content resolver stream away from caller dispatcher`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        stubResolver(bytes, mime = "video/mp4", size = bytes.size.toLong())
        val callerThread = Thread.currentThread()
        val readerThread = AtomicReference<Thread>()
        every { resolver.openInputStream(uri) } returns object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readerThread.set(Thread.currentThread())
                return super.read(buffer, offset, length)
            }
        }
        coEvery { attachmentApi.upload(any()) } coAnswers {
            firstArg<MultipartBody.Part>().body.writeTo(Buffer())
            Response.success(dto.withMimeType("video/mp4"))
        }

        repository.upload(uri).toList()

        assertTrue(readerThread.get() != null)
        assertNotSame(callerThread, readerThread.get())
    }

    @Test
    fun `oversize file fails typed before any request`() = runTest {
        stubResolver(ByteArray(4), size = AttachmentRepository.MAX_UPLOAD_BYTES + 1)

        val events = repository.upload(uri).toList()

        val failed = events.filterIsInstance<AttachmentUploadEvent.Failed>().single()
        assertEquals(AttachmentRepository.ERROR_TOO_LARGE, (failed.error as ApiError.Unknown).code)
        coVerify(exactly = 0) { attachmentApi.upload(any()) }
    }

    @Test
    fun `stream that outgrows the cap mid-read also fails typed`() = runTest {
        // statSize under-reports; the bounded read must still stop it.
        val bytes = ByteArray(AttachmentRepository.MAX_UPLOAD_BYTES.toInt() + 1)
        stubResolver(bytes, size = 100L)

        val events = repository.upload(uri).toList()

        assertTrue(events.last() is AttachmentUploadEvent.Failed)
        assertEquals(
            AttachmentRepository.ERROR_TOO_LARGE,
            ((events.last() as AttachmentUploadEvent.Failed).error as ApiError.Unknown).code,
        )
    }

    @Test
    fun `http error maps to ApiError`() = runTest {
        stubResolver(ByteArray(4), size = 4)
        coEvery { attachmentApi.upload(any()) } returns
            Response.error(413, "".toResponseBody())

        val events = repository.upload(uri).toList()

        val failed = events.filterIsInstance<AttachmentUploadEvent.Failed>().single()
        assertTrue(failed.error is ApiError.Unknown)
    }

    @Test
    fun `network failure maps to ApiError Network`() = runTest {
        stubResolver(ByteArray(4), size = 4)
        coEvery { attachmentApi.upload(any()) } throws java.io.IOException("down")

        val events = repository.upload(uri).toList()

        val failed = events.filterIsInstance<AttachmentUploadEvent.Failed>().single()
        assertTrue(failed.error is ApiError.Network)
    }

    // ---- MIME precedence: resolver type vs picker hint ----

    @Test
    fun `specific picker hint beats a generic resolver type`() = runTest {
        // A provider that cannot sniff HEIC answers application/octet-stream;
        // the picker knew it was an image. The old `meta.mimeType ?: hint`
        // shipped that upload as a FILE.
        stubResolver(ByteArray(4), mime = "application/octet-stream", size = 4)
        val sentType = AtomicReference<String?>(null)
        coEvery { attachmentApi.upload(any()) } coAnswers {
            val part = firstArg<MultipartBody.Part>()
            sentType.set(part.body.contentType()?.toString())
            Response.success(dto)
        }

        repository.upload(uri, mimeHint = "image/heic").toList()

        assertEquals("image/heic", sentType.get())
    }

    @Test
    fun `specific resolver type still beats a generic hint`() = runTest {
        stubResolver(ByteArray(4), mime = "video/mp4", size = 4)
        val sentType = AtomicReference<String?>(null)
        coEvery { attachmentApi.upload(any()) } coAnswers {
            val part = firstArg<MultipartBody.Part>()
            sentType.set(part.body.contentType()?.toString())
            Response.success(dto)
        }

        repository.upload(uri, mimeHint = "application/octet-stream").toList()

        assertEquals("video/mp4", sentType.get())
    }

    @Test
    fun `no usable type on either side falls back to octet-stream`() = runTest {
        stubResolver(ByteArray(4), mime = null, size = 4)
        val sentType = AtomicReference<String?>(null)
        coEvery { attachmentApi.upload(any()) } coAnswers {
            val part = firstArg<MultipartBody.Part>()
            sentType.set(part.body.contentType()?.toString())
            Response.success(dto)
        }

        repository.upload(uri, mimeHint = null).toList()

        assertEquals("application/octet-stream", sentType.get())
    }

    // ---- content-URI permission and download failure paths ----

    @Test
    fun `revoked uri permission fails typed instead of escaping the flow`() = runTest {
        stubResolver(ByteArray(4), size = 4)
        // The picker grant can be gone by upload time (activity recreated,
        // grant expired). An escaping SecurityException kills the caller's
        // viewModelScope and takes the chat screen down with it.
        every { resolver.openInputStream(uri) } throws SecurityException("PERMISSION_DENIED")

        val events = repository.upload(uri).toList()

        val failed = events.filterIsInstance<AttachmentUploadEvent.Failed>().single()
        assertEquals(
            AttachmentRepository.ERROR_UNREADABLE,
            (failed.error as ApiError.Unknown).code,
        )
        coVerify(exactly = 0) { attachmentApi.upload(any()) }
    }

    @Test
    fun `failed download leaves no truncated file in the cache`() = runTest {
        val cacheDir = java.nio.file.Files.createTempDirectory("nexa-attachments").toFile()
        every { context.cacheDir } returns cacheDir
        coEvery { attachmentApi.content("att-1") } returns Response.success(cutBody())

        val result = repository.downloadToCache("att-1", "pic.png")

        assertTrue(result is Result.Failure)
        val leftovers = File(cacheDir, AttachmentRepository.CACHE_DIR_NAME)
            .listFiles().orEmpty().map { it.name }
        // A half-written file is worse than no file: ExoPlayer/ACTION_VIEW are
        // handed the cache path and would open a corrupt copy.
        assertTrue("truncated download left behind: $leftovers", leftovers.isEmpty())
    }

    /** 2xx body whose stream dies mid-transfer. */
    private fun cutBody(): ResponseBody = object : ResponseBody() {
        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("cut")
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }
}
