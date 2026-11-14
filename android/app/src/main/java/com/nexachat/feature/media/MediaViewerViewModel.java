package com.nexachat.feature.media;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.data.AttachmentRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.File;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.inject.Inject;

/**
 * Viewer state is derived from navigation arguments and the download: images
 * stream through Glide, while other media resolves to a cache file.
 */
@HiltViewModel
public final class MediaViewerViewModel extends ViewModel {

    private final AttachmentRepository attachmentRepository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> mutableUiState;
    private UiState currentState;

    @Inject
    public MediaViewerViewModel(
            SavedStateHandle savedStateHandle,
            AttachmentRepository attachmentRepository) {
        this.attachmentRepository =
                Objects.requireNonNull(attachmentRepository, "attachmentRepository");
        String displayName = nonBlank(savedStateHandle.get("name"));
        String mimeType = nonBlank(savedStateHandle.get("mime"));
        String attachmentId = Objects.requireNonNullElse(
                savedStateHandle.<String>get("attachmentId"), "");
        String senderName = nonBlank(savedStateHandle.get("sender"));
        Long sentAt = savedStateHandle.get("sentAt");
        boolean isImage = mimeType != null
                && mimeType.startsWith(AttachmentDto.MIME_PREFIX_IMAGE);
        currentState = new UiState(
                attachmentId, displayName, mimeType, isImage, !isImage, null, null,
                senderName, sentAt == null ? 0L : sentAt);
        mutableUiState = new MutableLiveData<>(currentState);
        if (!isImage) {
            download();
        }
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    /** Retry hook for the error state — re-runs the same authorized download. */
    public void reload() {
        UiState state = currentState();
        if (state.isImage() || state.isLoading()) {
            return;
        }
        download();
    }

    private void download() {
        UiState state = currentState();
        updateState(current -> current.withLoading(true).withError(null));
        disposables.add(attachmentRepository.downloadToCache(
                        state.getAttachmentId(), state.getDisplayName())
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(current -> {
                            if (result instanceof Result.Success<?> success) {
                                return current.withLoading(false)
                                        .withFile((File) success.getValue())
                                        .withError(null);
                            }
                            return current.withLoading(false)
                                    .withError(((Result.Failure<?>) result).getError());
                        }),
                        error -> updateState(current -> current.withLoading(false)
                                .withError(new ApiError.Network(error.getMessage())))));
    }

    private synchronized UiState currentState() {
        return currentState;
    }

    private synchronized void updateState(UnaryOperator<UiState> reducer) {
        currentState = Objects.requireNonNull(reducer.apply(currentState), "state");
        mutableUiState.postValue(currentState);
    }

    private static String nonBlank(@Nullable String value) {
        if (value == null) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                return value;
            }
        }
        return null;
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }

    /** Immutable render state for the media viewer. */
    public static final class UiState {

        private final String attachmentId;
        @Nullable private final String displayName;
        @Nullable private final String mimeType;
        private final boolean image;
        private final boolean loading;
        @Nullable private final File file;
        @Nullable private final ApiError error;
        @Nullable private final String senderName;
        private final long sentAtEpochMs;

        public UiState() {
            this("", null, null, false, true, null, null, null, 0L);
        }

        private UiState(
                String attachmentId,
                @Nullable String displayName,
                @Nullable String mimeType,
                boolean image,
                boolean loading,
                @Nullable File file,
                @Nullable ApiError error,
                @Nullable String senderName,
                long sentAtEpochMs) {
            this.attachmentId = Objects.requireNonNull(attachmentId, "attachmentId");
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.image = image;
            this.loading = loading;
            this.file = file;
            this.error = error;
            this.senderName = senderName;
            this.sentAtEpochMs = sentAtEpochMs;
        }

        public String getAttachmentId() { return attachmentId; }
        @Nullable public String getDisplayName() { return displayName; }
        @Nullable public String getMimeType() { return mimeType; }
        public boolean isImage() { return image; }
        public boolean isLoading() { return loading; }
        public boolean getLoading() { return loading; }
        @Nullable public File getFile() { return file; }
        @Nullable public ApiError getError() { return error; }
        @Nullable public String getSenderName() { return senderName; }
        public long getSentAtEpochMs() { return sentAtEpochMs; }

        /** True for image/video attachments that present sender/time chrome. */
        public boolean isVisualMedia() {
            return image || (mimeType != null
                    && mimeType.startsWith(AttachmentDto.MIME_PREFIX_VIDEO));
        }

        public UiState withLoading(boolean value) {
            return new UiState(attachmentId, displayName, mimeType, image, value, file, error,
                    senderName, sentAtEpochMs);
        }

        public UiState withFile(@Nullable File value) {
            return new UiState(attachmentId, displayName, mimeType, image, loading, value, error,
                    senderName, sentAtEpochMs);
        }

        public UiState withError(@Nullable ApiError value) {
            return new UiState(attachmentId, displayName, mimeType, image, loading, file, value,
                    senderName, sentAtEpochMs);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof UiState that)) {
                return false;
            }
            return image == that.image
                    && loading == that.loading
                    && sentAtEpochMs == that.sentAtEpochMs
                    && attachmentId.equals(that.attachmentId)
                    && Objects.equals(displayName, that.displayName)
                    && Objects.equals(mimeType, that.mimeType)
                    && Objects.equals(file, that.file)
                    && Objects.equals(error, that.error)
                    && Objects.equals(senderName, that.senderName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(attachmentId, displayName, mimeType, image, loading, file, error,
                    senderName, sentAtEpochMs);
        }

        @Override
        public String toString() {
            return "UiState(attachmentId=" + attachmentId
                    + ", displayName=" + displayName
                    + ", mimeType=" + mimeType
                    + ", isImage=" + image
                    + ", loading=" + loading
                    + ", file=" + file
                    + ", error=" + error
                    + ", senderName=" + senderName
                    + ", sentAtEpochMs=" + sentAtEpochMs + ")";
        }
    }
}
