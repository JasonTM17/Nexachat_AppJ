package com.nexachat.summary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.lifecycle.SavedStateHandle;
import com.squareup.moshi.Moshi;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class SummaryViewModelTest {
    @Test
    public void requestMovesLoadingToReadyAndRestoresFromSavedIdAndCache() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            AtomicInteger calls = new AtomicInteger();
            try (SummaryTestServer server = new SummaryTestServer(exchange -> {
                calls.incrementAndGet();
                SummaryTestServer.respond(exchange, 200,
                        SummaryRepositoryTest.responseJson("summary-restore", false));
            })) {
                SummaryRepository repository = new SummaryRepository(
                        SummaryRepositoryTest.api(server.baseUrl()));
                SavedStateHandle firstHandle = new SavedStateHandle(
                        Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
                SummaryViewModel first = new SummaryViewModel(firstHandle, repository);

                assertEquals(SummaryUiState.Status.IDLE, first.state().getValue().getStatus());
                first.request(SummaryScope.UNREAD, null);
                SummaryUiState ready = awaitState(first, SummaryUiState.Status.READY);
                assertEquals("summary-restore", ready.getSummary().getSummaryId());
                assertEquals("UNREAD", firstHandle.get("summary_scope"));
                assertEquals("summary-restore", firstHandle.get("summary_last_id"));

                SavedStateHandle restoredHandle = new SavedStateHandle(Map.of(
                        SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1",
                        "summary_scope", "UNREAD",
                        "summary_last_id", "summary-restore"));
                SummaryViewModel restored = new SummaryViewModel(restoredHandle, repository);
                assertEquals(SummaryUiState.Status.READY, restored.state().getValue().getStatus());
                assertEquals("summary-restore", restored.state().getValue().getSummary().getSummaryId());
                assertEquals(1, calls.get());
            }
        }
    }

    @Test
    public void retryRepeatsFailedRequestAndRefreshBypassesCache() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> refreshFlag = new AtomicReference<>();
            try (SummaryTestServer server = new SummaryTestServer(exchange -> {
                int call = calls.incrementAndGet();
                String body = SummaryTestServer.requestBody(exchange);
                if (call == 1) {
                    SummaryTestServer.respond(exchange, 500,
                            "{\"error\":{\"code\":\"INTERNAL_ERROR\"}}");
                } else {
                    refreshFlag.set(body.contains("\"refresh\":true") ? "true" : "false");
                    SummaryTestServer.respond(exchange, 200,
                            SummaryRepositoryTest.responseJson("summary-" + call, false));
                }
            })) {
                SummaryRepository repository = new SummaryRepository(
                        SummaryRepositoryTest.api(server.baseUrl()));
                SavedStateHandle handle = new SavedStateHandle(
                        Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
                SummaryViewModel viewModel = new SummaryViewModel(handle, repository);

                viewModel.request(SummaryScope.UNREAD, null);
                SummaryUiState error = awaitState(viewModel, SummaryUiState.Status.ERROR);
                assertEquals(SummaryError.UNKNOWN, error.getError());

                viewModel.retry();
                SummaryUiState ready = awaitState(viewModel, SummaryUiState.Status.READY);
                assertEquals("summary-2", ready.getSummary().getSummaryId());
                assertEquals(2, calls.get());

                viewModel.refresh();
                SummaryUiState refreshed = awaitState(viewModel, SummaryUiState.Status.READY,
                        "summary-3");
                assertEquals("summary-3", refreshed.getSummary().getSummaryId());
                assertEquals("true", refreshFlag.get());
                assertEquals(3, calls.get());
            }
        }
    }

    @Test
    public void newerRequestCancelsOldResponseAndDismissClearsSavedSummaryId() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepository repository = mock(SummaryRepository.class);
            AtomicReference<SummaryRepository.Callback> staleCallback = new AtomicReference<>();
            when(repository.findCachedById(anyString(), any(), anyString())).thenReturn(Optional.empty());
            when(repository.request(anyString(), any(), any())).thenAnswer(invocation -> {
                SummaryRequest request = invocation.getArgument(1);
                SummaryRepository.Callback callback = invocation.getArgument(2);
                if (request.getScope() == SummaryScope.UNREAD) {
                    staleCallback.set(callback);
                    return (SummaryRepository.Cancellable) () -> { };
                }
                callback.onSuccess(summaryResponse("summary-new"));
                return (SummaryRepository.Cancellable) () -> { };
            });
            SavedStateHandle handle = new SavedStateHandle(
                    Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
            SummaryViewModel viewModel = new SummaryViewModel(handle, repository);

            viewModel.request(SummaryScope.UNREAD, null);
            assertEquals(SummaryUiState.Status.LOADING, viewModel.state().getValue().getStatus());
            viewModel.request(SummaryScope.TODAY, null);
            SummaryUiState ready = awaitState(viewModel, SummaryUiState.Status.READY, "summary-new");

            SummaryRepository.Callback stale = staleCallback.get();
            assertNotNull(stale);
            stale.onSuccess(summaryResponse("summary-old"));
            assertEquals("summary-new", ready.getSummary().getSummaryId());
            assertEquals("summary-new", viewModel.state().getValue().getSummary().getSummaryId());

            viewModel.dismiss();
            SummaryUiState idle = awaitState(viewModel, SummaryUiState.Status.IDLE);
            assertNull(idle.getLastSummaryId());
            assertNull(handle.get("summary_last_id"));
        }
    }

    private static SummaryResponse summaryResponse(String summaryId) throws Exception {
        return new Moshi.Builder().build().adapter(SummaryResponse.class).fromJson(
                SummaryRepositoryTest.responseJson(summaryId, false));
    }

    private static SummaryUiState awaitState(SummaryViewModel viewModel, SummaryUiState.Status status)
            throws InterruptedException {
        return awaitState(viewModel, status, null);
    }

    private static SummaryUiState awaitState(
            SummaryViewModel viewModel,
            SummaryUiState.Status status,
            String summaryId) throws InterruptedException {
        return LiveDataTestSupport.awaitSummaryState(viewModel.state(), status, summaryId);
    }
}
