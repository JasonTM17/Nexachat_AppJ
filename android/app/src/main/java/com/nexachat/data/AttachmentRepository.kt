package com.nexachat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.ApiResult
import com.nexachat.core.network.AttachmentApi
import com.nexachat.core.network.ProgressRequestBody
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.messageTypeForMime
import com.nexachat.core.network.safeApiCall
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Local metadata sniffed from a picked [Uri] before upload — what the preview
 * strip and the send pipeline need without re-reading the resolver twice.
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

/**
 * One upload's event stream: zero or more monotonic [Progress] fractions,
 * then exactly one terminal [Uploaded] or [Failed].
 */
sealed interface AttachmentUploadEvent {
    data class Progress(val fraction: Float) : AttachmentUploadEvent
    data class Uploaded(val attachment: AttachmentDto) : AttachmentUploadEvent
    data class Failed(val error: ApiError) : AttachmentUploadEvent
}

/**
 * Attachment transport boundary (phase-09): `POST /attachments` multipart
 * with live progress, `GET /attachments/{id}/content` downloads.
 *
 * The 25 MB server cap is mirrored client-side — a cheaper, earlier failure
 * ([ERROR_TOO_LARGE]) before any byte hits the wire. Downloads land in
 * `cacheDir/attachments` so IMAGE thumbs (Coil, authed loader), media
 * playback (ExoPlayer on the local file) and ACTION_VIEW share one path.
 */
@Singleton
class AttachmentRepository @Inject constructor(
    private val attachmentApi: AttachmentApi,
    @ApplicationContext private val context: Context,
) {

    // ---- local resolution ----

    /**
     * Resolves display name, size and MIME for a picker [Uri]. Every resolver
     * call is best-effort — pickers vary wildly in what they expose.
     */
    suspend fun describe(uri: Uri): LocalAttachment = withContext(Dispatchers.IO) {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size < 0L) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")
                    ?.use { size = it.statSize }
            }
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        LocalAttachment(
            uri = uri,
            displayName = name ?: uri.lastPathSegment,
            mimeType = mime,
            sizeBytes = size,
            type = messageTypeForMime(mime),
        )
    }

    // ---- upload ----

    /**
     * Uploads [uri] as the multipart `file` part. Emits [Progress] starting at
     * 0f and strictly non-decreasing while OkHttp streams the body, then one
     * terminal event: [Uploaded] with the server dto, or [Failed] with a typed
     * [ApiError] (`[ERROR_TOO_LARGE]` for the client-side size guard).
     */
    fun upload(uri: Uri, mimeHint: String? = null): Flow<AttachmentUploadEvent> = channelFlow {
        send(AttachmentUploadEvent.Progress(0f))
        val meta = describe(uri)
        val bytes = try {
            readBounded(uri, meta.sizeBytes)
        } catch (e: OversizeException) {
            send(AttachmentUploadEvent.Failed(ApiError.Unknown(ERROR_TOO_LARGE)))
            return@channelFlow
        } catch (e: IOException) {
            send(AttachmentUploadEvent.Failed(ApiError.Network(e.message)))
            return@channelFlow
        }
        val mime = meta.mimeType ?: mimeHint ?: DEFAULT_MIME
        val requestBody = ProgressRequestBody(
            delegate = bytes.toRequestBody(mime.toMediaTypeOrNull()),
            onProgress = { written, total ->
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
            PART_FILE,
            meta.displayName ?: DEFAULT_FILENAME,
            requestBody,
        )
        when (val result = safeApiCall { attachmentApi.upload(part) }) {
            is ApiResult.Success -> {
                // Terminal clamp — the body counter may never have hit exactly
                // 1f when the server replies before the last flush lands.
                trySend(AttachmentUploadEvent.Progress(1f))
                send(AttachmentUploadEvent.Uploaded(result.body))
            }
            is ApiResult.HttpError ->
                send(AttachmentUploadEvent.Failed(ApiError.from(result.status, result.errorCode)))
            is ApiResult.NetworkError ->
                send(AttachmentUploadEvent.Failed(ApiError.Network(result.cause.message)))
        }
    }

    /**
     * Reads the picker stream with a hard ceiling — counting bytes as they
     * arrive instead of trusting `statSize` (providers can under-report).
     */
    private fun readBounded(uri: Uri, declaredSize: Long): ByteArray {
        if (declaredSize > MAX_UPLOAD_BYTES) throw OversizeException()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("cannot open $uri")
        return input.use { stream ->
            val buffer = java.io.ByteArrayOutputStream(
                if (declaredSize in 1..MAX_UPLOAD_BYTES) declaredSize.toInt() else READ_BUFFER,
            )
            val chunk = ByteArray(READ_BUFFER)
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                if (buffer.size() + read > MAX_UPLOAD_BYTES) throw OversizeException()
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }
    }

    // ---- download ----

    /**
     * Downloads attachment bytes into `cacheDir/attachments` and returns the
     * file. Used by the media viewer (ExoPlayer plays the local copy — one
     * auth path, replayable offline) and by FILE bubbles before ACTION_VIEW.
     * Re-downloads on each call — cache freshness beats staleness at 25 MB.
     */
    suspend fun downloadToCache(
        attachmentId: String,
        displayName: String? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        when (val result = safeApiCall { attachmentApi.content(attachmentId) }) {
            is ApiResult.Success -> try {
                val dir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
                val file = File(dir, attachmentId + "-" + sanitize(displayName))
                result.body.byteStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                Result.Success(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Result.Failure(ApiError.Network(e.message))
            }
            is ApiResult.HttpError ->
                Result.Failure(ApiError.from(result.status, result.errorCode))
            is ApiResult.NetworkError ->
                Result.Failure(ApiError.Network(result.cause.message))
        }
    }

    /** FileProvider-safe leaf name — strip anything outside a conservative set. */
    private fun sanitize(name: String?): String =
        name?.replace(SAFE_NAME_REGEX, "_")?.take(MAX_NAME_LENGTH)?.ifBlank { null }
            ?: DEFAULT_FILENAME

    private class OversizeException : IOException()

    companion object {
        const val MAX_UPLOAD_BYTES = 25L * 1024L * 1024L
        const val ERROR_TOO_LARGE = "ATTACHMENT_TOO_LARGE"
        const val PART_FILE = "file"
        const val CACHE_DIR_NAME = "attachments"

        private const val DEFAULT_MIME = "application/octet-stream"
        private const val DEFAULT_FILENAME = "attachment"
        private const val READ_BUFFER = 64 * 1024
        private const val MAX_NAME_LENGTH = 80
        private val SAFE_NAME_REGEX = "[^A-Za-z0-9._-]".toRegex()
    }
}
