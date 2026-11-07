package com.nexachat.feature.media

import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.data.AttachmentRepository
import kotlinx.coroutines.flow.MutableStateFlow

/** Download path for the Java [MediaViewerViewModel]. */
internal suspend fun mediaViewerDownload(
    repository: AttachmentRepository,
    uiState: MutableStateFlow<MediaViewerUiState>,
) {
    val current = uiState.value
    uiState.value = current.withDownloadStarted()
    when (
        val result = repository.downloadToCache(current.attachmentId, current.displayName)
    ) {
        is Result.Success -> uiState.value = uiState.value.withFile(result.value)
        is Result.Failure -> uiState.value = uiState.value.withError(result.error)
    }
}

internal fun mediaViewerInitialState(
    attachmentId: String,
    displayName: String?,
    mimeType: String?,
): MediaViewerUiState {
    val image = mimeType?.startsWith(AttachmentDto.MIME_PREFIX_IMAGE) == true
    return MediaViewerUiState(attachmentId, displayName, mimeType, image, !image, null, null)
}
