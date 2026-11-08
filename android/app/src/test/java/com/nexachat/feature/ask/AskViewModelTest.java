package com.nexachat.feature.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.network.SessionEvents;
import java.util.Collections;
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
        assertEquals("  When is the migration?  ", viewModel.state().getValue().getDraft());
        assertSame(expected, viewModel.state().getValue().getResponse());

        viewModel.updateDraft("What time was never mentioned?");
        viewModel.submit();
        api.lastCall().succeed(response(null, "NOT_FOUND"));

        assertEquals(AskUiState.Status.NOT_FOUND, viewModel.state().getValue().getStatus());
        assertNull(viewModel.state().getValue().getResponse());
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
    }

    private static AskViewModel createViewModel(FakeAskApi api, SessionEvents sessionEvents) {
        SavedStateHandle savedStateHandle = new SavedStateHandle(
                Map.of(AskViewModel.ARG_CONVERSATION_ID, "conversation-1"));
        return new AskViewModel(savedStateHandle, new AskRepository(api), Runnable::run, sessionEvents);
    }

    private static AskResponse response(String answer, String mode) {
        return new AskResponse(answer, Collections.emptyList(), mode, new AskUsage(0, 0, false));
    }
}
