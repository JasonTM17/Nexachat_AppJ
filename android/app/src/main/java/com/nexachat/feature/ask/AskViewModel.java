package com.nexachat.feature.ask;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.network.SessionEvents;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.inject.Named;

/** Keeps the question draft and transient response in memory across sheet rotation. */
@HiltViewModel
public final class AskViewModel extends ViewModel {
    public static final String ARG_CONVERSATION_ID = "conversationId";

    private final SavedStateHandle savedStateHandle;
    private final AskRepository repository;
    private final Executor mainExecutor;
    private final MutableLiveData<AskUiState> state;
    private final Disposable sessionSubscription;

    private String draft = "";
    private String pendingConsentQuestion;
    private long requestGeneration;
    private boolean consentRetryUsed;
    private AskRepository.Cancellable activeRequest;

    @Inject
    public AskViewModel(
            SavedStateHandle savedStateHandle,
            AskRepository repository,
            @Named("summary_main") Executor mainExecutor,
            SessionEvents sessionEvents) {
        this.savedStateHandle = Objects.requireNonNull(savedStateHandle, "savedStateHandle");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.mainExecutor = Objects.requireNonNull(mainExecutor, "mainExecutor");
        this.state = new MutableLiveData<>(AskUiState.idle(draft));
        this.sessionSubscription = Objects.requireNonNull(sessionEvents, "sessionEvents")
                .getEvents()
                .subscribe(event -> {
                    if (event instanceof SessionEvents.Event.LoggedOut) {
                        this.mainExecutor.execute(this::clearForLogout);
                    }
                });
    }

    public LiveData<AskUiState> state() {
        return state;
    }

    public void updateDraft(@NonNull String value) {
        String next = Objects.requireNonNull(value, "value");
        if (draft.equals(next)) {
            return;
        }
        draft = next;
        pendingConsentQuestion = null;
        AskUiState current = state.getValue();
        if (current != null && current.getStatus() == AskUiState.Status.LOADING) {
            requestGeneration++;
            cancelActiveRequest();
        }
        state.setValue(AskUiState.idle(draft));
    }

    public void submit() {
        String question = draft.trim();
        if (question.isEmpty() || question.length() > 500) {
            state.setValue(AskUiState.error(draft, AskError.VALIDATION));
            return;
        }
        pendingConsentQuestion = null;
        startRequest(question, false);
    }

    public void retry() {
        submit();
    }

    public void cancel() {
        requestGeneration++;
        cancelActiveRequest();
        pendingConsentQuestion = null;
        state.setValue(AskUiState.idle(draft));
    }

    public void retryAfterConsentOnce() {
        AskUiState current = state.getValue();
        if (current == null || current.getStatus() != AskUiState.Status.CONSENT_REQUIRED
                || pendingConsentQuestion == null || consentRetryUsed) {
            return;
        }
        String question = pendingConsentQuestion;
        pendingConsentQuestion = null;
        startRequest(question, true);
    }

    public void declineConsent() {
        pendingConsentQuestion = null;
        state.setValue(AskUiState.error(draft, AskError.CONSENT_REQUIRED));
    }

    public void consentUpdateFailed() {
        pendingConsentQuestion = null;
        state.setValue(AskUiState.error(draft, AskError.UNKNOWN));
    }

    /** Clears all transient ask data when the sheet is closed. */
    public void close() {
        requestGeneration++;
        cancelActiveRequest();
        pendingConsentQuestion = null;
        draft = "";
        state.setValue(AskUiState.idle(draft));
    }

    @Override
    protected void onCleared() {
        requestGeneration++;
        cancelActiveRequest();
        pendingConsentQuestion = null;
        draft = "";
        sessionSubscription.dispose();
        super.onCleared();
    }

    private void startRequest(String question, boolean afterConsent) {
        requestGeneration++;
        long token = requestGeneration;
        cancelActiveRequest();
        consentRetryUsed = afterConsent;
        state.setValue(AskUiState.loading(draft));

        String conversationId = savedStateHandle.get(ARG_CONVERSATION_ID);
        if (conversationId == null || conversationId.trim().isEmpty()) {
            state.setValue(AskUiState.error(draft, AskError.CHAT_UNAVAILABLE));
            return;
        }

        AskRepository.Cancellable request = repository.ask(
                conversationId,
                new AskRequest(question, "AUTO"),
                new AskRepository.ResultCallback() {
                    @Override
                    public void onSuccess(@NonNull AskResponse response) {
                        publish(token, () -> {
                            activeRequest = null;
                            String answer = response.getAnswer();
                            if ("NOT_FOUND".equals(response.getMode())
                                    || answer == null || answer.trim().isEmpty()) {
                                state.setValue(AskUiState.notFound(draft));
                            } else {
                                state.setValue(AskUiState.answer(draft, response));
                            }
                        });
                    }

                    @Override
                    public void onError(@NonNull AskErrorMapping mapping) {
                        publish(token, () -> {
                            activeRequest = null;
                            if (mapping.getError() == AskError.CONSENT_REQUIRED
                                    && !consentRetryUsed) {
                                pendingConsentQuestion = question;
                                state.setValue(AskUiState.consentRequired(draft));
                            } else if (mapping.getError() == AskError.RATE_LIMITED) {
                                state.setValue(AskUiState.rateLimited(
                                        draft, mapping.getRetryAfterSeconds()));
                            } else {
                                state.setValue(AskUiState.error(draft, mapping.getError()));
                            }
                        });
                    }
                });
        if (token == requestGeneration
                && state.getValue() != null
                && state.getValue().getStatus() == AskUiState.Status.LOADING) {
            activeRequest = request;
        } else {
            request.cancel();
        }
    }

    private void publish(long token, Runnable update) {
        mainExecutor.execute(() -> {
            if (token == requestGeneration) {
                update.run();
            }
        });
    }

    private void clearForLogout() {
        requestGeneration++;
        cancelActiveRequest();
        pendingConsentQuestion = null;
        draft = "";
        state.setValue(AskUiState.idle(draft));
    }

    private void cancelActiveRequest() {
        if (activeRequest != null) {
            activeRequest.cancel();
            activeRequest = null;
        }
    }
}
