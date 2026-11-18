package com.nexachat.feature.feed;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
 * One post draft: text, an optional image and the submit sequence. The client post id is kept
 * across retries so a request that reached the server but lost its response cannot post twice.
 */
@HiltViewModel
public final class PostComposeViewModel extends ViewModel {

    /** What the screen has to tell the user after a submit attempt. */
    public enum Outcome { POSTED, FAILED, RATE_LIMITED, IMAGE_FAILED }

    /** One immutable snapshot of the draft. */
    public static final class UiState {
        private final String body;
        @Nullable private final Uri image;
        private final boolean busy;
        private final boolean canSubmit;
        private final int length;

        UiState(String body, @Nullable Uri image, boolean busy, boolean canSubmit, int length) {
            this.body = body;
            this.image = image;
            this.busy = busy;
            this.canSubmit = canSubmit;
            this.length = length;
        }

        @NonNull public String getBody() { return body; }

        @Nullable public Uri getImage() { return image; }

        public boolean isBusy() { return busy; }

        public boolean canSubmit() { return canSubmit; }

        /** Characters counted against {@link FeedRules#MAX_POST_LENGTH}. */
        public int getLength() { return length; }
    }

    private final FeedRepository repository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> uiState =
            new MutableLiveData<>(new UiState("", null, false, false, 0));
    private final MutableLiveData<Outcome> outcome = new MutableLiveData<>();
    private String clientPostId = UUID.randomUUID().toString();

    @Inject
    public PostComposeViewModel(@NonNull FeedRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @NonNull public LiveData<UiState> getUiState() { return uiState; }

    /** One-shot result of the last submit; consume with {@link #onOutcomeShown()}. */
    @NonNull public LiveData<Outcome> getOutcome() { return outcome; }

    public void setBody(@Nullable String body) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        String next = body == null ? "" : body;
        uiState.setValue(new UiState(
                next,
                state.image,
                state.busy,
                FeedRules.canSubmitPost(next, state.image != null),
                FeedRules.length(next)));
    }

    public void setImage(@Nullable Uri image) {
        UiState state = uiState.getValue();
        if (state == null) {
            return;
        }
        uiState.setValue(new UiState(
                state.body,
                image,
                state.busy,
                FeedRules.canSubmitPost(state.body, image != null),
                state.length));
    }

    public void submit() {
        UiState state = uiState.getValue();
        if (state == null || state.busy || !state.canSubmit) {
            return;
        }
        setBusy(true);
        String body = FeedRules.normalize(state.body);
        if (state.image == null) {
            createPost(body, null);
            return;
        }
        disposables.add(repository.uploadImage(state.image)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        image -> createPost(body, image.imageId()),
                        error -> finish(Outcome.IMAGE_FAILED)));
    }

    public void onOutcomeShown() {
        outcome.setValue(null);
    }

    private void createPost(@Nullable String body, @Nullable String imageId) {
        disposables.add(repository.createPost(body, imageId, clientPostId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(post -> finish(Outcome.POSTED),
                        error -> finish(FeedRules.isRateLimited(error)
                                ? Outcome.RATE_LIMITED
                                : Outcome.FAILED)));
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
        uiState.setValue(new UiState(
                state.body,
                state.image,
                busy,
                !busy && FeedRules.canSubmitPost(state.body, state.image != null),
                state.length));
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}
