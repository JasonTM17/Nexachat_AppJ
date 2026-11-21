package com.nexachat.feature.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.network.SessionEvents;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
