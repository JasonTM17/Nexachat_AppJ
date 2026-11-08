package com.nexachat.feature.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class AskRepositoryTest {
    @Test
    public void requestUsesFakeApiAndTrimsQuestionWithoutCachingResponse() {
        FakeAskApi api = new FakeAskApi();
        AskRepository repository = new AskRepository(api);
        AskResponse expected = answer("The migration is on Friday.");
        AtomicReference<AskResponse> received = new AtomicReference<>();

        repository.ask("conversation-1", new AskRequest("  When is it?  ", "AUTO"),
                callback(received, new AtomicReference<>()));

        assertEquals("conversation-1", api.conversationId);
        assertEquals("When is it?", api.request.getQuestion());
        api.lastCall().succeed(expected);
        assertSame(expected, received.get());

        repository.ask("conversation-1", new AskRequest("When is it?", "AUTO"),
                callback(new AtomicReference<>(), new AtomicReference<>()));
        assertEquals("A second ask creates a fresh call", 2, api.calls.size());
    }

    @Test
    public void cancelSuppressesLateResultAndCancelsFakeCall() {
        FakeAskApi api = new FakeAskApi();
        AskRepository repository = new AskRepository(api);
        AtomicInteger callbacks = new AtomicInteger();
        AskRepository.Cancellable request = repository.ask(
                "conversation-1", new AskRequest("Question?", "AUTO"),
                new AskRepository.ResultCallback() {
                    @Override public void onSuccess(AskResponse response) { callbacks.incrementAndGet(); }
                    @Override public void onError(AskErrorMapping error) { callbacks.incrementAndGet(); }
                });

        request.cancel();
        assertTrue(api.lastCall().isCanceled());
        api.lastCall().succeed(answer("late"));
        assertEquals(0, callbacks.get());
    }

    @Test
    public void mapsRetryDelayAndDoesNotCallApiForInvalidQuestion() {
        FakeAskApi api = new FakeAskApi();
        AskRepository repository = new AskRepository(api);
        AtomicReference<AskErrorMapping> error = new AtomicReference<>();
        repository.ask("conversation-1", new AskRequest("Question?", "AUTO"),
                callback(new AtomicReference<>(), error));
        api.lastCall().fail(429,
                "{\"error\":{\"code\":\"RATE_LIMITED\",\"details\":{\"retryAfterSeconds\":17}}}");
        assertEquals(AskError.RATE_LIMITED, error.get().getError());
        assertEquals(Long.valueOf(17L), error.get().getRetryAfterSeconds());

        AtomicReference<AskErrorMapping> invalid = new AtomicReference<>();
        repository.ask("conversation-1", new AskRequest(String.join("", Collections.nCopies(501, "x")), "AUTO"),
                callback(new AtomicReference<>(), invalid));
        assertEquals(AskError.VALIDATION, invalid.get().getError());
        assertEquals(1, api.calls.size());
    }

    @Test
    public void mapsNetworkFailuresWithoutExposingFailureText() {
        FakeAskApi api = new FakeAskApi();
        AskRepository repository = new AskRepository(api);
        AtomicReference<AskErrorMapping> error = new AtomicReference<>();
        repository.ask("conversation-1", new AskRequest("Question?", "AUTO"),
                callback(new AtomicReference<>(), error));

        api.lastCall().fail(new IOException("private request detail"));

        assertEquals(AskError.NETWORK, error.get().getError());
        assertFalse(error.get().toString().contains("private request detail"));
    }

    private static AskResponse answer(String text) {
        return new AskResponse(text, Collections.emptyList(), "LLM", new AskUsage(0, 0, false));
    }

    private static AskRepository.ResultCallback callback(
            AtomicReference<AskResponse> response,
            AtomicReference<AskErrorMapping> error) {
        return new AskRepository.ResultCallback() {
            @Override public void onSuccess(AskResponse value) { response.set(value); }
            @Override public void onError(AskErrorMapping value) { error.set(value); }
        };
    }
}
