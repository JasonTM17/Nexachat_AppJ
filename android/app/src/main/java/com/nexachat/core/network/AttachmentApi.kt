package com.nexachat.core.network

import com.nexachat.core.network.dto.AttachmentDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * `/api/v1/attachments` endpoints (phase-09 server contract).
 *
 * Upload is multipart part name `file` → 201/200 `AttachmentDto`; content is
 * authorized member-or-owner server-side, so every call must ride the
 * auth-intercepted OkHttp client (the Retrofit instance already does).
 * Same `Response<T>` convention as the other APIs.
 */
interface AttachmentApi {

    @Multipart
    @POST("api/v1/attachments")
    suspend fun upload(@Part file: MultipartBody.Part): Response<AttachmentDto>

    /** Raw bytes; the server sets a sniffed Content-Type. */
    @GET("api/v1/attachments/{id}/content")
    suspend fun content(@Path("id") attachmentId: String): Response<ResponseBody>
}

/**
 * [RequestBody] decorator reporting sink progress while OkHttp writes the
 * multipart body — the upload-progress pipe (phase-09).
 *
 * Progress arrives on the calling thread inside [writeTo]; [onProgress] must
 * therefore be non-suspending (`trySend` into a channel/StateFlow upstream).
 * Fractions are strictly non-decreasing — bytes only ever accumulate.
 */
class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesWritten: Long, contentLength: Long) -> Unit,
) : RequestBody() {

    override fun contentType() = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot() = delegate.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        val total = runCatching { contentLength() }.getOrDefault(-1L)
        val countingSink = object : ForwardingSink(sink) {
            var bytesWritten = 0L
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                onProgress(bytesWritten, total)
            }
        }
        val buffered = countingSink.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}
