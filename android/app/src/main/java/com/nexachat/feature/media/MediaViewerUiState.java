package com.nexachat.feature.media;

import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import java.io.File;
import java.util.Objects;

/** Immutable media-viewer state. Images skip the download; other types resolve a cache file. */
public record MediaViewerUiState(
        String attachmentId,
        @Nullable String displayName,
        @Nullable String mimeType,
        boolean isImage,
        boolean loading,
        @Nullable File file,
        @Nullable ApiError error) {

    public MediaViewerUiState {
        attachmentId = Objects.requireNonNull(attachmentId, "attachmentId");
    }

    public MediaViewerUiState withIdentity(
            String attachmentId, @Nullable String displayName, @Nullable String mimeType) {
        return new MediaViewerUiState(attachmentId, displayName, mimeType, false, true, null, null);
    }

    public MediaViewerUiState withImageDecision(boolean image) {
        return new MediaViewerUiState(
                attachmentId, displayName, mimeType, image, !image, file, error);
    }

    public MediaViewerUiState withDownloadStarted() {
        return new MediaViewerUiState(
                attachmentId, displayName, mimeType, isImage, true, file, null);
    }

    public MediaViewerUiState withFile(File downloaded) {
        return new MediaViewerUiState(
                attachmentId, displayName, mimeType, isImage, false, downloaded, null);
    }

    public MediaViewerUiState withError(ApiError failure) {
        return new MediaViewerUiState(
                attachmentId, displayName, mimeType, isImage, false, file, failure);
    }
}
