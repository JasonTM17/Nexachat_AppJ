package com.nexachat.feature.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelStore;
import com.nexachat.core.network.SessionEvents;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

public final class AskViewModelTest {
    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    @Test
    public void draftSurvivesRequestStatesAndAnswerCanBeNotFound() {
        FakeAskApi api = new FakeAskApi();
        AskViewModel viewModel = createViewModel(api, new SessionEvents());
        AskResponse expected = response("The migration is on Friday.", "LLM");

        assertEquals(AskUiState.Status.IDLE, viewModel.state().getValue().getStatus());
        viewModel.updateDraft("  When is the migration?  ");
        viewModel.submit();
        assertEquals(AskUiState.Status.LOADING, viewModel.state().getValue().getStatus());
        assertEquals("When is the migration?", api.request.getQuestion());
        api.lastCall().succeed(expected);

        assertEquals(AskUiState.Status.ANSWER, viewModel.state().getValue().getStatus());
        assertEquals("", viewModel.state().getValue().getDraft());
        assertSame(expected, viewModel.state().getValue().getResponse());
        assertEquals(1, viewModel.state().getValue().getTurns().size());

        viewModel.updateDraft("What time was never mentioned?");
        viewModel.submit();
        api.lastCall().succeed(response(null, "NOT_FOUND"));

        assertEquals(AskUiState.Status.NOT_FOUND, viewModel.state().getValue().getStatus());
        assertNull(viewModel.state().getValue().getResponse());
        assertFalse(viewModel.state().getValue().isContextOmitted());
        assertEquals(2, viewModel.state().getValue().getTurns().size());
        assertEquals("When is the migration?",
                viewModel.state().getValue().getTurns().get(0).getQuestion());
    }

    @Test
    public void followUpKeepsPreviousAnswerAndSendsPriorTurns() {
        FakeAskApi api = new FakeAskApi();
        AskViewModel viewModel = createViewModel(api, new SessionEvents());
        viewModel.updateDraft("When is the migration?");
        viewModel.submit();
        api.lastCall().succeed(response("Friday.", "LLM"));

        viewModel.updateDraft("Who owns it?");
        assertEquals(AskUiState.Status.ANSWER, viewModel.state().getValue().getStatus());
        assertEquals(1, viewModel.state().getValue().getTurns().size());
        assertFalse(viewModel.state().getValue().suggestionsVisible());

        viewModel.submit();
        List<AskPriorTurn> prior = api.request.getPriorTurns();
        assertEquals(1, prior.size());
        assertEquals("When is the migration?", prior.get(0).getQuestion());
        assertEquals("Friday.", prior.get(0).getAnswer());
        api.lastCall().succeed(response("An owns it.", "LLM"));

        assertEquals(2, viewModel.state().getValue().getTurns().size());
        assertEquals("An owns it.", viewModel.state().getValue().getResponse().getAnswer());
    }

    @Test
    public void suggestionSubmitsTheChipText() {
        FakeAskApi api = new FakeAskApi();
        AskViewModel viewModel = createViewModel(api, new SessionEvents());
        viewModel.submitSuggestion("What did we decide?");
        assertEquals(AskUiState.Status.LOADING, viewModel.state().getValue().getStatus());
        assertEquals("What did we decide?", api.request.getQuestion());
        assertNull(api.request.getPriorTurns());
    }

    @Test
    public void cancelDuringLoadingKeepsEarlierTurns() {
        FakeAskApi api = new FakeAskApi();
        AskViewModel viewModel = createViewModel(api, new SessionEvents());
        viewModel.updateDraft("First?");
        viewModel.submit();
        api.lastCall().succeed(response("First answer", "LLM"));

        viewModel.updateDraft("Second?");
        viewModel.submit();
        FakeAskApi.FakeCall pending = api.lastCall();
        viewModel.cancel();
        assertTrue(pending.isCanceled());
        assertEquals(AskUiState.Status.ANSWER, viewModel.state().getValue().getStatus());
        assertEquals(1, viewModel.state().getValue().getTurns().size());
        pending.succeed(response("late", "LLM"));
        assertEquals(1, viewModel.state().getValue().getTurns().size());
    }

    @Test
    public void contextOmittedSurvivesAnswerAndNotFoundStates() {
        FakeAskApi api = new FakeAskApi();
        AskViewModel viewModel = createViewModel(api, new SessionEvents());
        viewModel.updateDraft("Question?");
        viewModel.submit();
        api.lastCall().succeed(response(null, "NOT_FOUND", true));

        assertEquals(AskUiState.Status.NOT_FOUND, viewModel.state().getValue().getStatus());
        assertTrue(viewModel.state().getValue().isContextOmitted());

        viewModel.updateDraft("Another question?");
        viewModel.submit();
        api.lastCall().succeed(response("It is on Friday.", "LLM", true));

        assertEquals(AskUiState.Status.ANSWER, viewModel.state().getValue().getStatus());
        assertTrue(viewModel.state().getValue().isContextOmitted());
    }

    @Test
    public void mapsValidationRateLimitAndRetryableErrorStates() {
        FakeAskApi api = new FakeAskApi();
        AskViewModel viewModel = createViewModel(api, new SessionEvents());
        viewModel.updateDraft("Question?");
        viewModel.submit();
        api.lastCall().fail(400, "{\"error\":{\"code\":\"VALIDATION_FAILED\"}}");
        assertEquals(AskUiState.Status.ERROR, viewModel.state().getValue().getStatus());
        assertEquals(AskError.VALIDATION, viewModel.state().getValue().getError());

        viewModel.retry();
        api.lastCall().fail(429,
                "{\"error\":{\"code\":\"RATE_LIMITED\",\"details\":{\"retryAfterSeconds\":9}}}");
        assertEquals(AskUiState.Status.RATE_LIMITED, viewModel.state().getValue().getStatus());
        assertEquals(Long.valueOf(9L), viewModel.state().getValue().getRetryAfterSeconds());

        viewModel.retry();
        api.lastCall().fail(500, "{\"error\":{\"code\":\"INTERNAL_ERROR\"}}");
        assertEquals(AskUiState.Status.ERROR, viewModel.state().getValue().getStatus());
        assertEquals(AskError.UNKNOWN, viewModel.state().getValue().getError());
        assertEquals(1, viewModel.state().getValue().getTurns().size());
    }

    @Test
    public void consentRetryRunsOnceAndCancelDropsLateAnswer() {
        FakeAskApi api = new FakeAskApi();
        AskViewModel viewModel = createViewModel(api, new SessionEvents());
        viewModel.updateDraft("Question?");
        viewModel.submit();
        api.lastCall().fail(403,
                "{\"error\":{\"code\":\"AI_SUMMARY_CONSENT_REQUIRED\"}}");
        assertEquals(AskUiState.Status.CONSENT_REQUIRED, viewModel.state().getValue().getStatus());

        viewModel.retryAfterConsentOnce();
        assertEquals(2, api.calls.size());
        assertEquals(AskUiState.Status.LOADING, viewModel.state().getValue().getStatus());
        api.lastCall().fail(403,
                "{\"error\":{\"code\":\"CONSENT_REQUIRED\"}}");
        assertEquals(AskUiState.Status.ERROR, viewModel.state().getValue().getStatus());
        viewModel.retryAfterConsentOnce();
        assertEquals(2, api.calls.size());

        viewModel.submit();
        FakeAskApi.FakeCall pending = api.lastCall();
        viewModel.cancel();
        assertTrue(pending.isCanceled());
        pending.succeed(response("late", "LLM"));
        assertEquals(AskUiState.Status.IDLE, viewModel.state().getValue().getStatus());
    }

    @Test
    public void declineConsentDropsConsentStateWithoutReopeningTheDialog() {
        FakeAskApi api = new FakeAskApi();
        AskViewModel viewModel = createViewModel(api, new SessionEvents());
        viewModel.updateDraft("Question?");
        viewModel.submit();
        api.lastCall().fail(403,
                "{\"error\":{\"code\":\"AI_SUMMARY_CONSENT_REQUIRED\"}}");
        assertEquals(AskUiState.Status.CONSENT_REQUIRED,
                viewModel.state().getValue().getStatus());

        viewModel.declineConsent();

        AskUiState declined = viewModel.state().getValue();
        assertEquals(AskUiState.Status.IDLE, declined.getStatus());
        assertTrue("the consent turn must be dropped so render() cannot reopen the dialog",
                declined.getTurns().isEmpty());
        assertEquals("Question?", declined.getDraft());
    }

    @Test
    public void retryAfterFailureKeepsNewlyTypedDraft() {
        FakeAskApi api = new FakeAskApi();
        AskViewModel viewModel = createViewModel(api, new SessionEvents());
        viewModel.updateDraft("Old question?");
        viewModel.submit();
        api.lastCall().fail(500, "{\"error\":{\"code\":\"INTERNAL_ERROR\"}}");
        assertEquals(AskUiState.Status.ERROR, viewModel.state().getValue().getStatus());

        viewModel.updateDraft("New draft question?");
        viewModel.retry();

        assertEquals("retry resubmits the failed turn, not the new draft",
                "Old question?", api.request.getQuestion());
        assertEquals(AskUiState.Status.LOADING, viewModel.state().getValue().getStatus());
        assertEquals("New draft question?", viewModel.state().getValue().getDraft());

        api.lastCall().succeed(response("Answer", "LLM"));
        assertEquals("a typed draft must survive the retry answer",
                "New draft question?", viewModel.state().getValue().getDraft());
    }

    @Test
    public void logoutClearsDraftAndTransientAnswer() {
        FakeAskApi api = new FakeAskApi();
        SessionEvents sessionEvents = new SessionEvents();
        AskViewModel viewModel = createViewModel(api, sessionEvents);
        viewModel.updateDraft("Private question");
        viewModel.submit();
        api.lastCall().succeed(response("Private answer", "LLM"));
        assertEquals(AskUiState.Status.ANSWER, viewModel.state().getValue().getStatus());

        sessionEvents.emitLoggedOut();

        assertEquals(AskUiState.Status.IDLE, viewModel.state().getValue().getStatus());
        assertEquals("", viewModel.state().getValue().getDraft());
        assertNull(viewModel.state().getValue().getResponse());
        assertTrue(viewModel.state().getValue().getTurns().isEmpty());
    }

    @Test
    public void idleEmptySheetShowsSuggestions() {
        AskViewModel viewModel = createViewModel(new FakeAskApi(), new SessionEvents());
        assertTrue(viewModel.state().getValue().suggestionsVisible());
        viewModel.updateDraft("Hi");
        assertFalse(viewModel.state().getValue().suggestionsVisible());
    }

    @Test
    public void queuedSuccessCannotReplaceTheThreadAfterCancelOrDraftEdit() {
        for (boolean edit : new boolean[] {false, true}) {
            FakeAskApi api = new FakeAskApi();
            QueuedExecutor main = new QueuedExecutor();
            AskViewModel viewModel = new AskViewModel(conversationHandle(), new AskRepository(api),
                    main, new SessionEvents());
            viewModel.submitSuggestion("First question?");
            api.lastCall().succeed(response("First answer", "LLM"));
            main.drain();
            viewModel.submitSuggestion("Second question?");
            api.lastCall().succeed(response("Obsolete answer", "LLM"));
            if (edit) {
                viewModel.updateDraft("Edited draft");
            } else {
                viewModel.cancel();
            }

            main.drain();

            assertEquals(1, viewModel.state().getValue().getTurns().size());
            assertEquals("First answer", viewModel.state().getValue().getResponse().getAnswer());
            assertEquals(edit ? "Edited draft" : "Second question?",
                    viewModel.state().getValue().getDraft());
        }
    }

    @Test
    public void queuedSuccessAndErrorCannotPublishAfterCloseOrClear() {
        for (boolean clear : new boolean[] {false, true}) {
            FakeAskApi api = new FakeAskApi();
            QueuedExecutor main = new QueuedExecutor();
            AskViewModel viewModel = new AskViewModel(conversationHandle(), new AskRepository(api),
                    main, new SessionEvents());
            ViewModelStore store = new ViewModelStore();
            store.put("ask", viewModel);
            viewModel.submitSuggestion("Private question?");
            api.lastCall().succeed(response("Private answer", "LLM"));
            api.lastCall().fail(500, "{\"error\":{\"code\":\"INTERNAL_ERROR\"}}");
            if (clear) {
                store.clear();
            } else {
                viewModel.close();
            }

            main.drain();

            assertTrue(viewModel.state().getValue().getTurns().isEmpty());
            assertNull(viewModel.state().getValue().getResponse());
        }
    }

    @Test
    public void aLogoutEventInvalidatesAlreadyQueuedAnswersBeforeTheyCanBeShown() {
        FakeAskApi api = new FakeAskApi();
        QueuedExecutor main = new QueuedExecutor();
        SessionEvents sessions = new SessionEvents();
        AskViewModel viewModel = new AskViewModel(conversationHandle(), new AskRepository(api),
                main, sessions);
        AtomicInteger answersShown = new AtomicInteger();
        viewModel.state().observeForever(value -> {
            if (value.getStatus() == AskUiState.Status.ANSWER) {
                answersShown.incrementAndGet();
            }
        });
        viewModel.submitSuggestion("Private question?");
        api.lastCall().succeed(response("Private answer", "LLM"));

        sessions.emitLoggedOut();
        main.drain();

        assertEquals(0, answersShown.get());
        assertTrue(viewModel.state().getValue().getTurns().isEmpty());
    }

    @Test
    public void changingConversationRejectsQueuedCallbacksAndDropsOldPriorTurns() {
        FakeAskApi api = new FakeAskApi();
        QueuedExecutor main = new QueuedExecutor();
        SavedStateHandle saved = conversationHandle();
        AskViewModel viewModel = new AskViewModel(saved, new AskRepository(api), main,
                new SessionEvents());
        viewModel.submitSuggestion("First chat question?");
        api.lastCall().succeed(response("First chat answer", "LLM"));
        main.drain();
        viewModel.submitSuggestion("Old pending question?");
        api.lastCall().succeed(response("Old queued answer", "LLM"));
        saved.set(AskViewModel.ARG_CONVERSATION_ID, "conversation-2");

        main.drain();
        assertEquals(1, viewModel.state().getValue().getTurns().size());
        viewModel.submitSuggestion("New chat question?");

        assertEquals("conversation-2", api.conversationId);
        assertNull(api.request.getPriorTurns());
        assertTrue(viewModel.state().getValue().getTurns().isEmpty());
    }

    @Test
    public void loadingObserverCancellationPreventsAnObsoleteNetworkRequest() {
        FakeAskApi api = new FakeAskApi();
        AskViewModel viewModel = createViewModel(api, new SessionEvents());
        viewModel.state().observeForever(value -> {
            if (value.getStatus() == AskUiState.Status.LOADING) {
                viewModel.cancel();
            }
        });

        viewModel.submitSuggestion("Canceled at loading?");

        assertTrue(api.calls.isEmpty());
        assertEquals(AskUiState.Status.IDLE, viewModel.state().getValue().getStatus());
    }

    private static SavedStateHandle conversationHandle() {
        return new SavedStateHandle(Map.of(AskViewModel.ARG_CONVERSATION_ID, "conversation-1"));
    }

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queued.addLast(command); }
        private void drain() {
            while (!queued.isEmpty()) {
                queued.removeFirst().run();
            }
        }
    }

    private static AskViewModel createViewModel(FakeAskApi api, SessionEvents sessionEvents) {
        SavedStateHandle savedStateHandle = new SavedStateHandle(
                Map.of(AskViewModel.ARG_CONVERSATION_ID, "conversation-1"));
        return new AskViewModel(savedStateHandle, new AskRepository(api), Runnable::run, sessionEvents);
    }

    private static AskResponse response(String answer, String mode) {
        return response(answer, mode, false);
    }

    private static AskResponse response(String answer, String mode, boolean contextOmitted) {
        return new AskResponse(answer, Collections.emptyList(), mode,
                new AskUsage(0, 0, false), contextOmitted);
    }
}
