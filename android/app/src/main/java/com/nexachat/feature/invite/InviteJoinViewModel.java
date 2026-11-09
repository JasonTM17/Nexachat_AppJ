package com.nexachat.feature.invite;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import java.util.Optional;
import javax.inject.Inject;

/** Validates invite URLs and drives preview, join, and request status states. */
@HiltViewModel
public final class InviteJoinViewModel extends ViewModel {
    private final InviteRepository repository;
    private final MutableLiveData<InviteJoinUiState> mutableState =
            new MutableLiveData<>(InviteJoinUiState.loading());
    private final CompositeDisposable disposables = new CompositeDisposable();
    @Nullable private String token;

    @Inject
    public InviteJoinViewModel(@NonNull InviteRepository repository) {
        this.repository = repository;
    }

    public LiveData<InviteJoinUiState> getState() {
        return mutableState;
    }

    @Nullable
    String inviteTokenForSignIn() {
        return token;
    }

    public void open(@Nullable String rawUrl) {
        Optional<InviteLinkParser.ParsedInvite> parsed = InviteLinkParser.parse(rawUrl);
        if (parsed.isEmpty()) {
            mutableState.setValue(InviteJoinUiState.loading()
                    .withFailure(InviteFailure.UNAVAILABLE));
            return;
        }
        token = parsed.get().getToken();
        mutableState.setValue(InviteJoinUiState.loading());
        loadPreview();
    }

    public void join() {
        String inviteToken = token;
        InviteJoinUiState state = current();
        if (inviteToken == null || state.getStatus() != InviteJoinUiState.Status.PREVIEW
                || state.isBusy()) {
            return;
        }
        mutableState.setValue(state.withBusy());
        disposables.add(repository.join(inviteToken)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onJoined, this::showFailure));
    }

    public void refresh() {
        InviteJoinUiState state = current();
        if (state.getStatus() == InviteJoinUiState.Status.PENDING
                || state.getStatus() == InviteJoinUiState.Status.APPROVED) {
            refreshStatus();
        } else if (state.getStatus() == InviteJoinUiState.Status.ERROR && token != null) {
            mutableState.setValue(InviteJoinUiState.loading());
            loadPreview();
        } else {
            refreshStatus();
        }
    }

    public void refreshStatus() {
        InviteJoinUiState state = current();
        String id = state.getConversationId();
        if (id == null || (state.getStatus() != InviteJoinUiState.Status.PENDING
                && state.getStatus() != InviteJoinUiState.Status.APPROVED)) {
            return;
        }
        mutableState.setValue(state.withBusy());
        disposables.add(repository.getMyJoinRequest(id)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onRequestStatus, this::showFailure));
    }

    private void loadPreview() {
        String inviteToken = token;
        if (inviteToken == null) {
            return;
        }
        disposables.add(repository.loadPreview(inviteToken)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(preview -> mutableState.setValue(current().withPreview(preview)),
                        this::showFailure));
    }

    private void onJoined(JoinResponse response) {
        InviteJoinUiState.Status next = switch (response.status == null ? "" : response.status) {
            case "JOINED" -> InviteJoinUiState.Status.JOINED;
            case "ALREADY_MEMBER" -> InviteJoinUiState.Status.ALREADY_MEMBER;
            case "PENDING", "PENDING_APPROVAL" -> InviteJoinUiState.Status.PENDING;
            default -> InviteJoinUiState.Status.ERROR;
        };
        if (next == InviteJoinUiState.Status.ERROR) {
            showFailure(new InviteApiException(InviteFailure.UNKNOWN));
            return;
        }
        mutableState.setValue(current().withStatus(next, response.conversationId));
        if (next == InviteJoinUiState.Status.PENDING) {
            refreshStatus();
        }
    }

    private void onRequestStatus(Optional<JoinRequestResponse> result) {
        if (result.isEmpty()) {
            InviteJoinUiState current = current();
            mutableState.setValue(current.withStatus(
                    InviteJoinUiState.Status.PENDING, current.getConversationId()));
            return;
        }
        JoinRequestResponse request = result.get();
        InviteJoinUiState.Status next = switch (request.status == null ? "" : request.status) {
            case "PENDING" -> InviteJoinUiState.Status.PENDING;
            case "APPROVED" -> InviteJoinUiState.Status.APPROVED;
            case "REJECTED" -> InviteJoinUiState.Status.REJECTED;
            default -> InviteJoinUiState.Status.PENDING;
        };
        mutableState.setValue(current().withStatus(next, request.conversationId));
    }

    private void showFailure(Throwable error) {
        InviteFailure failure = error instanceof InviteApiException inviteError
                ? inviteError.getFailure()
                : InviteFailure.UNKNOWN;
        mutableState.setValue(current().withFailure(failure));
    }

    private InviteJoinUiState current() {
        InviteJoinUiState current = mutableState.getValue();
        return current == null ? InviteJoinUiState.loading() : current;
    }

    @Override
    protected void onCleared() {
        token = null;
        disposables.clear();
    }
}
