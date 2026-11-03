package com.nexachat.core.network.dto

import com.nexachat.core.common.AppConfig
import com.squareup.moshi.JsonClass

/**
 * Attachment metadata (plan phase-09 multimedia contract).
 *
 * This is BOTH the `POST /api/v1/attachments` upload response
 * `{id,mimeType,sizeBytes,originalName,url?}` and the `attachments[]` element
 * embedded in message payloads — extra fields a server build may emit
 * (`storageKey`, dimensions, duration) are tolerated here so one type serves
 * every appearance.
 */
@JsonClass(generateAdapter = true)
data class AttachmentDto(
    val id: String? = null,
    val storageKey: String? = null,
    val originalName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val url: String? = null,
) {
    /**
     * Absolute URL serving the raw bytes. The upload response may carry a
     * `url` (absolute or server-relative); when it does not, bytes come from
     * `GET /api/v1/attachments/{id}/content` (member-or-owner authZ — always
     * fetched through the authed OkHttp stack).
     */
    fun contentUrl(): String? {
        val direct = url
        return when {
            direct.isNullOrBlank() -> id?.let { contentPath(it) }
            direct.startsWith("http://") || direct.startsWith("https://") -> direct
            direct.startsWith("/") -> AppConfig.API_BASE_URL + direct
            else -> "${AppConfig.API_BASE_URL}/$direct"
        }
    }

    val isImage: Boolean get() = mimeType?.startsWith(MIME_PREFIX_IMAGE) == true
    val isVideo: Boolean get() = mimeType?.startsWith(MIME_PREFIX_VIDEO) == true
    val isAudio: Boolean get() = mimeType?.startsWith(MIME_PREFIX_AUDIO) == true

    /** Message `type` this attachment rides in a send payload. */
    val messageType: String get() = messageTypeForMime(mimeType)

    companion object {
        const val MIME_PREFIX_IMAGE = "image/"
        const val MIME_PREFIX_VIDEO = "video/"
        const val MIME_PREFIX_AUDIO = "audio/"

        fun contentPath(attachmentId: String): String =
            "${AppConfig.API_BASE_URL}/api/v1/attachments/$attachmentId/content"
    }
}

/** Derives the wire message `type` from a MIME string; anything else is FILE. */
fun messageTypeForMime(mimeType: String?): String = when {
    mimeType?.startsWith(AttachmentDto.MIME_PREFIX_IMAGE) == true -> MessageDto.TYPE_IMAGE
    mimeType?.startsWith(AttachmentDto.MIME_PREFIX_VIDEO) == true -> MessageDto.TYPE_VIDEO
    mimeType?.startsWith(AttachmentDto.MIME_PREFIX_AUDIO) == true -> MessageDto.TYPE_AUDIO
    else -> MessageDto.TYPE_FILE
}
