package com.nexachat.data

import android.net.Uri
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.ApiResult
import com.nexachat.core.network.ProgressRequestBody
import com.nexachat.core.network.safeApiCall
import com.nexachat.core.network.dto.AttachmentDto
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Coroutine-facing API implemented by the Java attachment repository.
 * Keeping the suspend declarations here preserves member-call semantics for
 * ViewModels and MockK tests while Java delegates coroutine machinery below.
 */
interface AttachmentRepositoryApi {
    suspend fun describe(uri: Uri): LocalAttachment

    fun upload(uri: Uri, mimeHint: String? = null): Flow<AttachmentUploadEvent>

    suspend fun downloadToCache(
        attachmentId: String,
        displayName: String? = null,
    ): Result<File>
}

/**
 * Local metadata sniffed from a picked [Uri] before upload — what the preview
 * strip and send pipeline need without re-reading the resolver twice.
 */
data class LocalAttachment(
    val uri: Uri,
    val displayName: String?,
    val mimeType: String?,
    /** Best-effort size; -1 when the provider does not report one. */
    val sizeBytes: Long,
    /** Wire `type` derived from the MIME prefix (IMAGE/VIDEO/AUDIO/FILE). */
    val type: String,
)

/** One upload stream: zero or more progress events, then one terminal result. */
sealed interface AttachmentUploadEvent {
    data class Progress(val fraction: Float) : AttachmentUploadEvent
    data class Uploaded(val attachment: AttachmentDto) : AttachmentUploadEvent
    data class Failed(val error: ApiError) : AttachmentUploadEvent
}

/** Kotlin coroutine boundary for [AttachmentRepository]. */
object AttachmentRepositoryFlow {

    @JvmStatic
    suspend fun describe(
        repository: AttachmentRepository,
        uri: Uri,
    ): LocalAttachment = withContext(Dispatchers.IO) {
        repository.describeOnIo(uri)
    }

    @JvmStatic
    fun upload(
        repository: AttachmentRepository,
        uri: Uri,
        mimeHint: String?,
    ): Flow<AttachmentUploadEvent> = channelFlow {
        send(AttachmentUploadEvent.Progress(0f))
        val meta = describe(repository, uri)
        val bytes = try {
            withContext(Dispatchers.IO) { repository.readBounded(uri, meta.sizeBytes) }
        } catch (e: AttachmentRepository.OversizeException) {
            send(AttachmentUploadEvent.Failed(ApiError.Unknown(AttachmentRepository.ERROR_TOO_LARGE)))
            return@channelFlow
        } catch (e: IOException) {
            send(AttachmentUploadEvent.Failed(ApiError.Network(e.message)))
            return@channelFlow
        } catch (e: SecurityException) {
            // A picker grant may expire between selection and upload. Surface
            // a typed error so it cannot cancel the caller's ViewModel scope.
            send(AttachmentUploadEvent.Failed(ApiError.Unknown(AttachmentRepository.ERROR_UNREADABLE)))
            return@channelFlow
        }

        val mime = repository.resolveMime(meta.mimeType, mimeHint)
        val requestBody = ProgressRequestBody(
            bytes.toRequestBody(mime.toMediaTypeOrNull()),
            ProgressRequestBody.ProgressListener { written, total ->
                if (total > 0L) {
                    trySend(
                        AttachmentUploadEvent.Progress(
                            (written.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                        ),
                    )
                }
            },
        )
        val part = MultipartBody.Part.createFormData(
            AttachmentRepository.PART_FILE,
            meta.displayName ?: DEFAULT_FILENAME,
            requestBody,
        )

        when (val result = safeApiCall(repository.attachmentApi().upload(part))) {
            is ApiResult.Success -> {
                // The body counter may not have emitted exactly 1f before the
                // server answers, so clamp progress before the terminal event.
                trySend(AttachmentUploadEvent.Progress(1f))
                send(AttachmentUploadEvent.Uploaded(result.body))
            }
            is ApiResult.HttpError -> send(
                AttachmentUploadEvent.Failed(ApiError.from(result.status, result.errorCode)),
            )
            is ApiResult.NetworkError -> send(
                AttachmentUploadEvent.Failed(ApiError.Network(result.cause.message)),
            )
        }
    }

    @JvmStatic
    suspend fun downloadToCache(
        repository: AttachmentRepository,
        attachmentId: String,
        displayName: String?,
    ): Result<File> = withContext(Dispatchers.IO) {
        when (val result = safeApiCall(repository.attachmentApi().content(attachmentId))) {
            is ApiResult.Success -> {
                val directory = File(repository.cacheDir(), AttachmentRepository.CACHE_DIR_NAME)
                    .apply { mkdirs() }
                val file = File(directory, "$attachmentId-${sanitize(displayName)}")
                try {
                    result.body.byteStream().use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    Result.Success(file)
                } catch (e: CancellationException) {
                    runCatching { file.delete() }
                    throw e
                } catch (e: IOException) {
                    // Never leave a truncated file that media viewers could open.
                    runCatching { file.delete() }
                    Result.failure(ApiError.Network(e.message))
                }
            }
            is ApiResult.HttpError -> Result.failure(
                ApiError.from(result.status, result.errorCode),
            )
            is ApiResult.NetworkError -> Result.failure(ApiError.Network(result.cause.message))
        }
    }

    /** FileProvider-safe leaf name — strip anything outside a conservative set. */
    private fun sanitize(name: String?): String =
        name?.replace(SAFE_NAME_REGEX, "_")?.take(MAX_NAME_LENGTH)?.ifBlank { null }
            ?: DEFAULT_FILENAME

    private const val DEFAULT_FILENAME = "attachment"
    private const val MAX_NAME_LENGTH = 80
    private val SAFE_NAME_REGEX = "[^A-Za-z0-9._-]".toRegex()
}
