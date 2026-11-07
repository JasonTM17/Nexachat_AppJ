package com.nexachat.feature.media;

import androidx.annotation.Nullable;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import com.nexachat.data.AttachmentRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.Objects;
import javax.inject.Inject;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/** Viewer state from nav args plus an authorized cache download for non-images. */
@HiltViewModel
public final class MediaViewerViewModel extends ViewModel {

    private final AttachmentRepository attachmentRepository;
    private final MutableStateFlow<MediaViewerUiState> mutableUiState;
    private final StateFlow<MediaViewerUiState> uiState;

    @Inject
    public MediaViewerViewModel(
            SavedStateHandle savedStateHandle, AttachmentRepository attachmentRepository) {
        this.attachmentRepository =
                Objects.requireNonNull(attachmentRepository, "attachmentRepository");
        String attachmentId = savedStateHandle.get("attachmentId");
        if (attachmentId == null) {
            attachmentId = "";
        }
        this.mutableUiState = StateFlowKt.MutableStateFlow(
                MediaViewerViewModelSupportKt.mediaViewerInitialState(
                        attachmentId,
                        blankToNull(savedStateHandle.get("name")),
                        blankToNull(savedStateHandle.get("mime"))));
        this.uiState = mutableUiState;
        if (!mutableUiState.getValue().isImage()) {
            download();
        }
    }

    public StateFlow<MediaViewerUiState> getUiState() {
        return uiState;
    }

    /** Retry hook for the error state. Images and in-flight downloads are ignored. */
    public void reload() {
        MediaViewerUiState current = mutableUiState.getValue();
        if (current.isImage() || current.loading()) {
            return;
        }
        download();
    }

    private void download() {
        BuildersKt.launch(
                ViewModelKt.getViewModelScope(this),
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                (Function2<CoroutineScope, Continuation<? super Unit>, Object>)
                        (scope, continuation) ->
                                MediaViewerViewModelSupportKt.mediaViewerDownload(
                                        attachmentRepository, mutableUiState, continuation));
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
