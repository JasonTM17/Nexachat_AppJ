package com.nexachat.feature.invite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;

/** Coordinates administrator link settings and pending join requests. */
@HiltViewModel
public final class InviteManageViewModel extends ViewModel {
    private final InviteRepository repository;
    private final MutableLiveData<InviteManageUiState> mutableState =
            new MutableLiveData<>(InviteManageUiState.initial());
    private final CompositeDisposable disposables = new CompositeDisposable();
    @Nullable private String conversationId;

    @Inject
    public InviteManageViewModel(@NonNull InviteRepository repository) {
        this.repository = repository;
    }

    public LiveData<InviteManageUiState> getState() {
        return mutableState;
    }

    public void load(@NonNull String id) {
        if (id.isBlank() || id.equals(conversationId)) {
            return;
        }
        conversationId = id;
        mutableState.setValue(InviteManageUiState.initial());
        disposables.add(
                io.reactivex.rxjava3.core.Single.zip(
                                repository.getActiveLink(id),
                                repository.listPendingRequests(id),
                                InviteManageLoad::new)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                loaded -> mutableState.setValue(
                                        current().withLoaded(loaded.link, loaded.requests)),
                                this::showFailure));
    }

    public void createOrRotate(
            @Nullable String expiresAt,
            @Nullable Integer maxUses,
            boolean requiresApproval) {
        String id = conversationId;
        if (id == null || current().isBusy()) {
            return;
        }
        mutableState.setValue(current().withBusy(true));
        disposables.add(repository.createOrRotateLink(
                        id, new InviteLinkRequest(expiresAt, maxUses, requiresApproval))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onLinkCreated, this::showFailure));
    }

    public void setRequiresApproval(boolean enabled) {
        InviteLinkResponse link = current().getLink();
        String id = conversationId;
        if (id == null || link == null || link.requiresApproval == enabled || current().isBusy()) {
            return;
        }
        mutableState.setValue(current().withBusy(true));
        disposables.add(repository.updateApproval(id, enabled)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(response -> mutableState.setValue(
                                current().withLink(response, current().getShareUrl())),
                        this::showFailure));
    }

    public void revoke() {
        String id = conversationId;
        if (id == null || current().getLink() == null || current().isBusy()) {
            return;
        }
        mutableState.setValue(current().withBusy(true));
        disposables.add(repository.revokeLink(id)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> mutableState.setValue(current().withLink(null, null)),
                        this::showFailure));
    }

    public void approve(@NonNull String requestId) {
        review(requestId, true);
    }

    public void reject(@NonNull String requestId) {
        review(requestId, false);
    }

    public void refreshRequests() {
        String id = conversationId;
        if (id == null || current().isBusy()) {
            return;
        }
        disposables.add(repository.listPendingRequests(id)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(requests -> mutableState.setValue(current().withRequests(requests)),
                        this::showFailure));
    }

    public void dismissFailure() {
        mutableState.setValue(current().clearFailure());
    }

    private void review(String requestId, boolean approve) {
        String id = conversationId;
        if (id == null || current().isBusy()) {
            return;
        }
        mutableState.setValue(current().withBusy(true));
        Completable action = approve
                ? repository.approveRequest(id, requestId)
                : repository.rejectRequest(id, requestId);
        disposables.add(action
                .andThen(repository.listPendingRequests(id))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(requests -> mutableState.setValue(current().withRequests(requests)),
                        this::showFailure));
    }

    private void onLinkCreated(InviteLinkResponse response) {
        Optional<String> shareUrl = InviteLinkParser.shareUrl(response.token);
        if (shareUrl.isEmpty()) {
            showFailure(new InviteApiException(InviteFailure.UNKNOWN));
            return;
        }
        mutableState.setValue(current().withLink(response, shareUrl.get()));
    }

    private void showFailure(Throwable error) {
        InviteFailure failure = error instanceof InviteApiException inviteError
                ? inviteError.getFailure()
                : InviteFailure.UNKNOWN;
        mutableState.setValue(current().withFailure(failure));
    }

    private InviteManageUiState current() {
        InviteManageUiState current = mutableState.getValue();
        return current == null ? InviteManageUiState.initial() : current;
    }

    @Override
    protected void onCleared() {
        disposables.clear();
    }

    private static final class InviteManageLoad {
        private final Optional<InviteLinkResponse> link;
        private final List<JoinRequestResponse> requests;

        private InviteManageLoad(
                Optional<InviteLinkResponse> link,
                List<JoinRequestResponse> requests) {
            this.link = link;
            this.requests = requests;
        }
    }
}
