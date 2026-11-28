package com.nexachat.feature.feed;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import java.util.UUID;
import javax.inject.Inject;

/**
 * One post draft (or edit): text, optional photo OR MP4 video, runtime videoUploadsEnabled
 * flag check, and retry-safe media ID caching so retrying after a network error does not
 * re-upload media or duplicate posts.
 */
@HiltViewModel
public final class PostComposeViewModel extends ViewModel {

    /** What the screen has to tell the user after a submit attempt. */
    public enum Outcome {
        POSTED,
        EDITED,
        FAILED,
        RATE_LIMITED,
        IMAGE_FAILED,
        VIDEO_FAILED,
        VIDEO_DISABLED,
        VIDEO_TOO_LARGE,
        VIDEO_INVALID_TYPE
    }

    /** One immutable snapshot of the draft. */
    public static final class UiState {
        private final String body;
        @Nullable private final Uri image;
        @Nullable private final Uri video;
        private final boolean busy;
        private final boolean canSubmit;
        private final int length;
        private final boolean videoUploadsEnabled;
        private final boolean editMode;

        UiState(String body, @Nullable Uri image, boolean busy, boolean canSubmit, int length) {
            this(body, image, null, busy, canSubmit, length, true, false);
        }

        UiState(
                String body,
                @Nullable Uri image,
                @Nullable Uri video,
                boolean busy,
                boolean canSubmit,
                int length,
                boolean videoUploadsEnabled,
                boolean editMode) {
            this.body = body;
            this.image = image;
            this.video = video;
            this.busy = busy;
            this.canSubmit = canSubmit;
            this.length = length;
            this.videoUploadsEnabled = videoUploadsEnabled;
            this.editMode = editMode;
        }

        @NonNull public String getBody() { return body; }

        @Nullable public Uri getImage() { return image; }

        @Nullable public Uri getVideo() { return video; }

        public boolean isBusy() { return busy; }

        public boolean canSubmit() { return canSubmit; }

        /** Characters counted against {@link FeedRules#MAX_POST_LENGTH}. */
        public int getLength() { return length; }

        public boolean isVideoUploadsEnabled() { return videoUploadsEnabled; }

        public boolean isEditMode() { return editMode; }
    }

    private final FeedRepository repository;
    private final FeedSyncBus syncBus;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(
            new UiState("", null, null, false, false, 0, true, false));
    private final MutableLiveData<Outcome> outcome = new MutableLiveData<>();
    private final String clientPostId = UUID.randomUUID().toString();
    @Nullable private String editPostId;
    @Nullable private Uri uploadedImageUri;
    @Nullable private String uploadedImageId;
    @Nullable private Uri uploadedVideoUri;
    @Nullable private String uploadedVideoId;

    @VisibleForTesting
    public PostComposeViewModel(@NonNull FeedRepository repository) {
        this(repository, new FeedSyncBus());
    }

    @Inject
    public PostComposeViewModel(
            @NonNull FeedRepository repository, @NonNull FeedSyncBus syncBus) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.syncBus = Objects.requireNonNull(syncBus, "syncBus");
        refreshVideoConfig();
    }

    @NonNull public LiveData<UiState> getUiState() { return uiState; }

    /** One-shot result of the last submit; consume with {@link #onOutcomeShown()}. */
    @NonNull public LiveData<Outcome> getOutcome() { return outcome; }

    /** Switches this draft into editing an existing post's body. */
    public void startEdit(@NonNull String postId, @Nullable String initialBody) {
        if (Objects.equals(editPostId, postId)) {
            return;
        }
        editPostId = postId;
        String safeBody = initialBody == null ? "" : initialBody;
        uploadedImageUri = null;
        uploadedImageId = null;
        uploadedVideoUri = null;
        uploadedVideoId = null;
        UiState current = uiState.getValue();
        boolean videoEnabled = current == null || current.videoUploadsEnabled;
        uiState.setValue(new UiState(
                safeBody,
                null,
                null,
                false,
                FeedRules.canSubmitEditedPost(safeBody),
                FeedRules.length(safeBody),
                videoEnabled,
                true));
    }

    public void setBody(@Nullable String body) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        String next = body == null ? "" : body;
        boolean canSubmit = state.editMode
                ? FeedRules.canSubmitEditedPost(next)
                : FeedRules.canSubmitPost(next, state.image != null, state.video != null);
        uiState.setValue(new UiState(
                next,
                state.image,
                state.video,
                state.busy,
                !state.busy && canSubmit,
                FeedRules.length(next),
                state.videoUploadsEnabled,
                state.editMode));
    }

    public void setImage(@Nullable Uri image) {
        UiState state = uiState.getValue();
        if (state == null || state.editMode) {
            return;
        }
        if (!Objects.equals(state.image, image)) {
            uploadedImageUri = null;
            uploadedImageId = null;
        }
        if (image != null) {
            uploadedVideoUri = null;
            uploadedVideoId = null;
        }
        Uri nextVideo = image != null ? null : state.video;
        boolean canSubmit = FeedRules.canSubmitPost(state.body, image != null, nextVideo != null);
        uiState.setValue(new UiState(
                state.body,
                image,
                nextVideo,
                state.busy,
                !state.busy && canSubmit,
                state.length,
                state.videoUploadsEnabled,
                false));
    }

    public void setVideo(@Nullable Uri video) {
        UiState state = uiState.getValue();
        if (state == null || state.editMode) {
            return;
        }
        if (!Objects.equals(state.video, video)) {
            uploadedVideoUri = null;
            uploadedVideoId = null;
        }
        if (video != null) {
            uploadedImageUri = null;
            uploadedImageId = null;
        }
        Uri nextImage = video != null ? null : state.image;
        boolean canSubmit = FeedRules.canSubmitPost(state.body, nextImage != null, video != null);
        uiState.setValue(new UiState(
                state.body,
                nextImage,
                video,
                state.busy,
                !state.busy && canSubmit,
                state.length,
                state.videoUploadsEnabled,
                false));
    }

    public void submit() {
        UiState state = uiState.getValue();
        if (state == null || state.busy || !state.canSubmit) {
            return;
        }
        String body = FeedRules.normalize(state.body);
        if (state.editMode && editPostId != null) {
            if (body == null) {
                return;
            }
            setBusy(true);
            disposables.add(repository.editPost(editPostId, body)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(updated -> {
                        syncBus.publish(new FeedSyncBus.PostUpdated(updated));
                        finish(Outcome.EDITED);
                    }, error -> finish(FeedRules.isRateLimited(error)
                            ? Outcome.RATE_LIMITED
                            : Outcome.FAILED)));
            return;
        }

        setBusy(true);
        if (state.image != null) {
            if (Objects.equals(state.image, uploadedImageUri) && uploadedImageId != null) {
                createPost(body, uploadedImageId, null);
                return;
            }
            Uri targetUri = state.image;
            disposables.add(repository.uploadImage(targetUri)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(uploaded -> {
                        uploadedImageUri = targetUri;
                        uploadedImageId = uploaded.imageId();
                        createPost(body, uploaded.imageId(), null);
                    }, error -> finish(Outcome.IMAGE_FAILED)));
            return;
        }

        if (state.video != null) {
            if (!state.videoUploadsEnabled) {
                finish(Outcome.VIDEO_DISABLED);
                return;
            }
            if (Objects.equals(state.video, uploadedVideoUri) && uploadedVideoId != null) {
                createPost(body, null, uploadedVideoId);
                return;
            }
            Uri targetUri = state.video;
            disposables.add(repository.uploadVideo(targetUri)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(uploaded -> {
                        uploadedVideoUri = targetUri;
                        uploadedVideoId = uploaded.videoId();
                        createPost(body, null, uploaded.videoId());
                    }, error -> finish(mapVideoError(error))));
            return;
        }

        createPost(body, null, null);
    }

    public void onOutcomeShown() {
        outcome.setValue(null);
    }

    private void refreshVideoConfig() {
        disposables.add(repository.getSocialConfig()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(config -> {
                    UiState state = uiState.getValue();
                    if (state == null) {
                        return;
                    }
                    uiState.setValue(new UiState(
                            state.body,
                            state.image,
                            state.video,
                            state.busy,
                            state.canSubmit,
                            state.length,
                            config.videoUploadsEnabled(),
                            state.editMode));
                }, ignored -> { }));
    }

    private void createPost(
            @Nullable String body, @Nullable String imageId, @Nullable String videoId) {
        disposables.add(repository.createPost(body, imageId, videoId, clientPostId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(post -> {
                    syncBus.publish(new FeedSyncBus.PostCreated(post));
                    finish(Outcome.POSTED);
                }, error -> {
                    if (FeedRules.isSocialFeatureDisabled(error)) {
                        finish(Outcome.VIDEO_DISABLED);
                    } else if (FeedRules.isRateLimited(error)) {
                        finish(Outcome.RATE_LIMITED);
                    } else {
                        finish(Outcome.FAILED);
                    }
                }));
    }

    private static Outcome mapVideoError(@NonNull Throwable error) {
        if (FeedRules.isSocialFeatureDisabled(error)) {
            return Outcome.VIDEO_DISABLED;
        }
        if (FeedRules.isFileTooLarge(error)) {
            return Outcome.VIDEO_TOO_LARGE;
        }
        if (FeedRules.isMimeNotAllowed(error)) {
            return Outcome.VIDEO_INVALID_TYPE;
        }
        if (FeedRules.isRateLimited(error)) {
            return Outcome.RATE_LIMITED;
        }
        return Outcome.VIDEO_FAILED;
    }

    private void finish(Outcome result) {
        setBusy(false);
        outcome.setValue(result);
    }

    private void setBusy(boolean busy) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        boolean canSubmit = state.editMode
                ? FeedRules.canSubmitEditedPost(state.body)
                : FeedRules.canSubmitPost(state.body, state.image != null, state.video != null);
        uiState.setValue(new UiState(
                state.body,
                state.image,
                state.video,
                busy,
                !busy && canSubmit,
                state.length,
                state.videoUploadsEnabled,
                state.editMode));
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}
