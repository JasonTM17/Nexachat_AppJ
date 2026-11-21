package com.nexachat.feature.ask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.network.SessionEvents;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.inject.Named;

/** Keeps the question draft, sheet thread, and transient response across rotation. */
@HiltViewModel
public final class AskViewModel extends ViewModel {
    public static final String ARG_CONVERSATION_ID = "conversationId";
    static final int MAX_PRIOR_TURNS = 3;

    private final SavedStateHandle savedStateHandle;
    private final AskRepository repository;
    private final Executor mainExecutor;
    private final MutableLiveData<AskUiState> state;
    private final Disposable sessionSubscription;

    private String draft = "";
    private String pendingConsentQuestion;
    private String boundConversationId;
    private volatile boolean sessionInvalidated;
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
        this.boundConversationId = savedStateHandle.get(ARG_CONVERSATION_ID);
        this.sessionSubscription = Objects.requireNonNull(sessionEvents, "sessionEvents")
                .getEvents()
                .subscribe(event -> {
                    if (event instanceof SessionEvents.Event.LoggedOut) {
                        // Fence answers already queued ahead of the main-thread logout cleanup.
                        sessionInvalidated = true;
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
        publishTurns(false);
    }

    public void submitSuggestion(@NonNull String suggestion) {
        updateDraft(Objects.requireNonNull(suggestion, "suggestion"));
        submit();
    }

    public void submit() {
        String question = draft.trim();
        if (question.isEmpty() || question.length() > 500) {
            state.setValue(AskUiState.fromTurns(
                    draft, AskUiState.append(currentTurns(), AskTurn.error(question, AskError.VALIDATION)),
                    false));
            return;
        }
        pendingConsentQuestion = null;
        startRequest(question, false);
    }

    public void retry() {
        AskTurn last = lastTurn();
        if (last != null && (last.getStatus() == AskTurn.Status.ERROR
                || last.getStatus() == AskTurn.Status.RATE_LIMITED
                || last.getStatus() == AskTurn.Status.CONSENT_REQUIRED)) {
            if (draft.isBlank()) {
                draft = last.getQuestion();
            }
            startRequest(last.getQuestion(), false);
            return;
        }
        submit();
    }

    public void cancel() {
        requestGeneration++;
        cancelActiveRequest();
        pendingConsentQuestion = null;
        publishTurns(false);
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
        List<AskTurn> turns = currentTurns();
        if (draft.isEmpty()) {
            AskTurn last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
            if (last != null && last.getStatus() == AskTurn.Status.CONSENT_REQUIRED) {
                draft = last.getQuestion();
            }
        }
        state.setValue(AskUiState.fromTurns(draft, dropLastConsentTurn(turns), false));
    }

    public void consentUpdateFailed() {
        pendingConsentQuestion = null;
        List<AskTurn> turns = dropLastConsentTurn(currentTurns());
        state.setValue(AskUiState.fromTurns(
                draft, AskUiState.append(turns, AskTurn.error(lastQuestion(), AskError.UNKNOWN)),
                false));
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
        String conversationId = savedStateHandle.get(ARG_CONVERSATION_ID);
        if (!Objects.equals(boundConversationId, conversationId)) {
            boundConversationId = conversationId;
            pendingConsentQuestion = null;
            state.setValue(AskUiState.idle(draft));
        }
        if (sessionInvalidated) {
            return;
        }
        consentRetryUsed = afterConsent;
        List<AskTurn> turns = turnsForNewRequest(question);
        state.setValue(AskUiState.loading(draft, turns));

        // Loading observers can synchronously cancel or switch the active conversation.
        if (!isCurrent(token, conversationId)) {
            return;
        }
        if (conversationId == null || conversationId.trim().isEmpty()) {
            state.setValue(AskUiState.fromTurns(
                    draft, AskUiState.append(turns, AskTurn.error(question, AskError.CHAT_UNAVAILABLE)),
                    false));
            return;
        }

        AskRepository.Cancellable request = repository.ask(
                conversationId,
                new AskRequest(question, "AUTO", priorTurns(turns)),
                new AskRepository.ResultCallback() {
                    @Override
                    public void onSuccess(@NonNull AskResponse response) {
                        publish(token, conversationId, () -> {
                            activeRequest = null;
                            String answer = response.getAnswer();
                            AskTurn turn;
                            String nextDraft = draft;
                            if ("NOT_FOUND".equals(response.getMode())
                                    || answer == null || answer.trim().isEmpty()) {
                                turn = AskTurn.notFound(question, response.isContextOmitted());
                            } else {
                                turn = AskTurn.answer(question, response);
                                if (draft.trim().equals(question)) {
                                    draft = "";
                                }
                                nextDraft = draft;
                            }
                            state.setValue(AskUiState.fromTurns(
                                    nextDraft, AskUiState.append(turns, turn), false));
                        });
                    }

                    @Override
                    public void onError(@NonNull AskErrorMapping mapping) {
                        publish(token, conversationId, () -> {
                            activeRequest = null;
                            AskTurn turn;
                            if (mapping.getError() == AskError.CONSENT_REQUIRED
                                    && !consentRetryUsed) {
                                pendingConsentQuestion = question;
                                turn = AskTurn.consentRequired(question);
                            } else if (mapping.getError() == AskError.RATE_LIMITED) {
                                turn = AskTurn.rateLimited(question, mapping.getRetryAfterSeconds());
                            } else {
                                turn = AskTurn.error(question, mapping.getError());
                            }
                            state.setValue(AskUiState.fromTurns(
                                    draft, AskUiState.append(turns, turn), false));
                        });
                    }
                });
        if (isCurrent(token, conversationId)
                && state.getValue() != null
                && state.getValue().getStatus() == AskUiState.Status.LOADING) {
            activeRequest = request;
        } else {
            request.cancel();
        }
    }

    private boolean isCurrent(long token, String conversationId) {
        return token == requestGeneration && !sessionInvalidated
                && Objects.equals(conversationId, savedStateHandle.get(ARG_CONVERSATION_ID));
    }

    private void publish(long token, String conversationId, Runnable update) {
        mainExecutor.execute(() -> {
            if (isCurrent(token, conversationId)) {
                update.run();
            }
        });
    }

    private void publishTurns(boolean loading) {
        state.setValue(AskUiState.fromTurns(draft, currentTurns(), loading));
    }

    private List<AskTurn> currentTurns() {
        AskUiState current = state.getValue();
        return current == null ? List.of() : current.getTurns();
    }

    private List<AskTurn> turnsForNewRequest(String question) {
        List<AskTurn> turns = currentTurns();
        if (turns.isEmpty()) {
            return turns;
        }
        AskTurn last = turns.get(turns.size() - 1);
        if (last.getQuestion().equals(question) && last.getStatus() != AskTurn.Status.ANSWER) {
            return new ArrayList<>(turns.subList(0, turns.size() - 1));
        }
        return turns;
    }

    @Nullable
    private AskTurn lastTurn() {
        List<AskTurn> turns = currentTurns();
        return turns.isEmpty() ? null : turns.get(turns.size() - 1);
    }

    private String lastQuestion() {
        AskTurn last = lastTurn();
        return last == null ? draft.trim() : last.getQuestion();
    }

    private static List<AskTurn> dropLastConsentTurn(List<AskTurn> turns) {
        if (turns.isEmpty()) {
            return turns;
        }
        AskTurn last = turns.get(turns.size() - 1);
        if (last.getStatus() != AskTurn.Status.CONSENT_REQUIRED) {
            return turns;
        }
        return new ArrayList<>(turns.subList(0, turns.size() - 1));
    }

    static List<AskPriorTurn> priorTurns(List<AskTurn> turns) {
        List<AskPriorTurn> prior = new ArrayList<>();
        if (turns == null) {
            return prior;
        }
        for (int index = turns.size() - 1; index >= 0 && prior.size() < MAX_PRIOR_TURNS; index--) {
            AskTurn turn = turns.get(index);
            if (turn.getStatus() != AskTurn.Status.ANSWER || turn.getResponse() == null) {
                continue;
            }
            String answer = turn.getResponse().getAnswer();
            if (answer == null || answer.isBlank()) {
                continue;
            }
            prior.add(0, new AskPriorTurn(turn.getQuestion(), answer));
        }
        return prior;
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
