package com.nexachat.summary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelStore;
import com.squareup.moshi.Moshi;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;
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
    public void requestSendsTheDeviceZoneAndKeepsTheChosenCount() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            AtomicReference<String> body = new AtomicReference<>();
            try (SummaryTestServer server = new SummaryTestServer(exchange -> {
                body.set(SummaryTestServer.requestBody(exchange));
                SummaryTestServer.respond(exchange, 200,
                        SummaryRepositoryTest.responseJson("summary-zone", false));
            })) {
                SummaryRepository repository = new SummaryRepository(
                        SummaryRepositoryTest.api(server.baseUrl()));
                SavedStateHandle handle = new SavedStateHandle(
                        Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
                SummaryViewModel viewModel = new SummaryViewModel(handle, repository);

                viewModel.request(SummaryScope.LAST_N, 20);
                SummaryUiState ready = awaitState(viewModel, SummaryUiState.Status.READY);

                assertEquals(Integer.valueOf(20), ready.getLimit());
                assertTrue(body.get().contains("\"scope\":\"LAST_N\""));
                assertTrue(body.get().contains("\"limit\":20"));
                assertTrue(body.get().contains(
                        "\"timeZone\":\"" + TimeZone.getDefault().getID() + "\""));
                assertEquals(Integer.valueOf(20), handle.get("summary_limit"));
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
    public void invalidTimeZoneRetriesOnceWithoutAZoneAndDeliversTheResult() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> retryBody = new AtomicReference<>();
            try (SummaryTestServer server = new SummaryTestServer(exchange -> {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    SummaryTestServer.respond(exchange, 400,
                            "{\"error\":{\"code\":\"INVALID_TIME_ZONE\",\"message\":\"synthetic\"}}");
                } else {
                    retryBody.set(SummaryTestServer.requestBody(exchange));
                    SummaryTestServer.respond(exchange, 200,
                            SummaryRepositoryTest.responseJson("summary-default-zone", false));
                }
            })) {
                SummaryRepository repository = new SummaryRepository(
                        SummaryRepositoryTest.api(server.baseUrl()));
                SavedStateHandle handle = new SavedStateHandle(
                        Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
                SummaryViewModel viewModel = new SummaryViewModel(handle, repository);

                viewModel.request(SummaryScope.TODAY, null);
                SummaryUiState ready = awaitState(viewModel, SummaryUiState.Status.READY);

                assertEquals("summary-default-zone", ready.getSummary().getSummaryId());
                assertEquals(2, calls.get());
                assertFalse(retryBody.get().contains("\"refresh\":true"));
                assertFalse(retryBody.get().contains("\"timeZone\""));
            }
        }
    }

    @Test
    public void refreshRetriesWithoutAZoneAndKeepsForcingAFreshGeneration() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> retryBody = new AtomicReference<>();
            try (SummaryTestServer server = new SummaryTestServer(exchange -> {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    SummaryTestServer.respond(exchange, 400,
                            "{\"error\":{\"code\":\"INVALID_TIME_ZONE\",\"message\":\"synthetic\"}}");
                } else {
                    retryBody.set(SummaryTestServer.requestBody(exchange));
                    SummaryTestServer.respond(exchange, 200,
                            SummaryRepositoryTest.responseJson("summary-default-zone", false));
                }
            })) {
                SummaryRepository repository = new SummaryRepository(
                        SummaryRepositoryTest.api(server.baseUrl()));
                SavedStateHandle handle = new SavedStateHandle(Map.of(
                        SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1",
                        "summary_scope", "TODAY"));
                SummaryViewModel viewModel = new SummaryViewModel(handle, repository);

                viewModel.refresh();
                SummaryUiState ready = awaitState(viewModel, SummaryUiState.Status.READY);

                assertEquals("summary-default-zone", ready.getSummary().getSummaryId());
                assertEquals(2, calls.get());
                assertTrue(retryBody.get().contains("\"refresh\":true"));
                assertFalse(retryBody.get().contains("\"timeZone\""));
            }
        }
    }

    @Test
    public void aSecondInvalidTimeZoneStopsAtTheGenericError() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            AtomicInteger calls = new AtomicInteger();
            try (SummaryTestServer server = new SummaryTestServer(exchange -> {
                calls.incrementAndGet();
                SummaryTestServer.respond(exchange, 400,
                        "{\"error\":{\"code\":\"INVALID_TIME_ZONE\",\"message\":\"synthetic\"}}");
            })) {
                SummaryRepository repository = new SummaryRepository(
                        SummaryRepositoryTest.api(server.baseUrl()));
                SavedStateHandle handle = new SavedStateHandle(
                        Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
                SummaryViewModel viewModel = new SummaryViewModel(handle, repository);

                viewModel.request(SummaryScope.TODAY, null);
                SummaryUiState error = awaitState(viewModel, SummaryUiState.Status.ERROR);

                assertEquals(SummaryError.UNKNOWN, error.getError());
                assertEquals(2, calls.get());
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

    @Test
    public void revalidateReRequestsATodaySummaryFromAnotherDay() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepository repository = mock(SummaryRepository.class);
            AtomicInteger calls = new AtomicInteger();
            String zone = TimeZone.getDefault().getID();
            long now = Instant.parse("2026-11-17T05:00:00Z").toEpochMilli();
            String yesterday = Instant.ofEpochMilli(now).atZone(ZoneId.of(zone))
                    .toLocalDate().minusDays(1).toString();
            String today = Instant.ofEpochMilli(now).atZone(ZoneId.of(zone))
                    .toLocalDate().toString();
            when(repository.findCachedById(anyString(), any(), anyString())).thenReturn(Optional.empty());
            when(repository.request(anyString(), any(), any())).thenAnswer(invocation -> {
                int call = calls.incrementAndGet();
                SummaryRepository.Callback callback = invocation.getArgument(2);
                callback.onSuccess(todaySummary("summary-" + call,
                        call == 1 ? yesterday : today, zone));
                return (SummaryRepository.Cancellable) () -> { };
            });
            SavedStateHandle handle = new SavedStateHandle(
                    Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
            SummaryViewModel viewModel = new SummaryViewModel(handle, repository, Runnable::run,
                    () -> now);

            viewModel.request(SummaryScope.TODAY, null);
            awaitState(viewModel, SummaryUiState.Status.READY, "summary-1");
            assertEquals(1, calls.get());

            viewModel.revalidate();
            awaitState(viewModel, SummaryUiState.Status.READY, "summary-2");
            assertEquals(2, calls.get());

            // The new result already names today, so a second resume keeps it.
            viewModel.revalidate();
            assertEquals(2, calls.get());
        }
    }

    @Test
    public void revalidateLeavesScopesWithoutADayAlone() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepository repository = mock(SummaryRepository.class);
            AtomicInteger calls = new AtomicInteger();
            when(repository.findCachedById(anyString(), any(), anyString())).thenReturn(Optional.empty());
            when(repository.request(anyString(), any(), any())).thenAnswer(invocation -> {
                calls.incrementAndGet();
                SummaryRepository.Callback callback = invocation.getArgument(2);
                callback.onSuccess(summaryResponse("summary-unread"));
                return (SummaryRepository.Cancellable) () -> { };
            });
            SavedStateHandle handle = new SavedStateHandle(
                    Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
            SummaryViewModel viewModel = new SummaryViewModel(handle, repository);

            viewModel.request(SummaryScope.UNREAD, null);
            awaitState(viewModel, SummaryUiState.Status.READY, "summary-unread");
            viewModel.revalidate();

            assertEquals(1, calls.get());
        }
    }

    @Test
    public void aStaleInvalidTimeZoneNeverStartsTheZoneLessRetry() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepository repository = mock(SummaryRepository.class);
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<SummaryRepository.Callback> staleCallback = new AtomicReference<>();
            when(repository.findCachedById(anyString(), any(), anyString())).thenReturn(Optional.empty());
            when(repository.request(anyString(), any(), any())).thenAnswer(invocation -> {
                calls.incrementAndGet();
                SummaryRepository.Callback callback = invocation.getArgument(2);
                if (invocation.getArgument(1, SummaryRequest.class).getScope() == SummaryScope.UNREAD) {
                    staleCallback.set(callback);
                    return (SummaryRepository.Cancellable) () -> { };
                }
                callback.onSuccess(summaryResponse("summary-new"));
                return (SummaryRepository.Cancellable) () -> { };
            });
            SavedStateHandle handle = new SavedStateHandle(
                    Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
            SummaryViewModel viewModel = new SummaryViewModel(handle, repository);

            // Supersede the first request so its callback is stale when the zone is rejected.
            viewModel.request(SummaryScope.UNREAD, null);
            viewModel.request(SummaryScope.TODAY, null);
            awaitState(viewModel, SummaryUiState.Status.READY, "summary-new");

            SummaryRepository.Callback stale = staleCallback.get();
            assertNotNull(stale);
            stale.onError(SummaryError.INVALID_TIME_ZONE);

            assertEquals(2, calls.get());
        }
    }

    @Test
    public void revalidateKeepsATodaySummaryWhoseZoneTheServerDefaulted() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepository repository = mock(SummaryRepository.class);
            AtomicInteger calls = new AtomicInteger();
            String zone = TimeZone.getDefault().getID();
            String otherZone = zone.equals("Asia/Ho_Chi_Minh") ? "UTC" : "Asia/Ho_Chi_Minh";
            long now = Instant.parse("2026-11-17T05:00:00Z").toEpochMilli();
            String today = Instant.ofEpochMilli(now).atZone(ZoneId.of(zone))
                    .toLocalDate().toString();
            when(repository.findCachedById(anyString(), any(), anyString())).thenReturn(Optional.empty());
            when(repository.request(anyString(), any(), any())).thenAnswer(invocation -> {
                int call = calls.incrementAndGet();
                SummaryRepository.Callback callback = invocation.getArgument(2);
                callback.onSuccess(todaySummary("summary-" + call, today, otherZone, true));
                return (SummaryRepository.Cancellable) () -> { };
            });
            SavedStateHandle handle = new SavedStateHandle(
                    Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
            SummaryViewModel viewModel = new SummaryViewModel(handle, repository, Runnable::run,
                    () -> now);

            viewModel.request(SummaryScope.TODAY, null);
            awaitState(viewModel, SummaryUiState.Status.READY, "summary-1");

            // The zone-less retry produced this result, so the server's own day is authoritative
            // and a resume must not re-request forever.
            viewModel.revalidate();
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void revalidateStillReRequestsATodaySummaryFromAnotherZone() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepository repository = mock(SummaryRepository.class);
            AtomicInteger calls = new AtomicInteger();
            String zone = TimeZone.getDefault().getID();
            String otherZone = zone.equals("Asia/Ho_Chi_Minh") ? "UTC" : "Asia/Ho_Chi_Minh";
            long now = Instant.parse("2026-11-17T05:00:00Z").toEpochMilli();
            String today = Instant.ofEpochMilli(now).atZone(ZoneId.of(zone))
                    .toLocalDate().toString();
            when(repository.findCachedById(anyString(), any(), anyString())).thenReturn(Optional.empty());
            when(repository.request(anyString(), any(), any())).thenAnswer(invocation -> {
                int call = calls.incrementAndGet();
                SummaryRepository.Callback callback = invocation.getArgument(2);
                callback.onSuccess(todaySummary("summary-" + call, today, otherZone, false));
                return (SummaryRepository.Cancellable) () -> { };
            });
            SavedStateHandle handle = new SavedStateHandle(
                    Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
            SummaryViewModel viewModel = new SummaryViewModel(handle, repository, Runnable::run,
                    () -> now);

            viewModel.request(SummaryScope.TODAY, null);
            awaitState(viewModel, SummaryUiState.Status.READY, "summary-1");

            viewModel.revalidate();
            awaitState(viewModel, SummaryUiState.Status.READY, "summary-2");
            assertEquals(2, calls.get());
        }
    }

    @Test
    public void clearingTheViewModelRejectsSuccessAlreadyQueuedForPublication() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            ControlledRepository controlled = new ControlledRepository();
            QueuedExecutor main = new QueuedExecutor();
            SavedStateHandle saved = conversationHandle();
            SummaryViewModel viewModel = new SummaryViewModel(saved, controlled.repository, main,
                    System::currentTimeMillis);
            ViewModelStore store = new ViewModelStore();
            store.put("summary", viewModel);
            viewModel.request(SummaryScope.UNREAD, null);
            main.drain();
            controlled.callbacks.get(0).onSuccess(summaryResponse("obsolete"));

            store.clear();
            main.drain();

            assertEquals(SummaryUiState.Status.LOADING, viewModel.state().getValue().getStatus());
            assertNull(saved.get("summary_last_id"));
            assertEquals(1, controlled.canceled.get());
        }
    }

    @Test
    public void aQueuedTimezoneErrorCannotRetryAfterDismiss() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            ControlledRepository controlled = new ControlledRepository();
            QueuedExecutor main = new QueuedExecutor();
            SummaryViewModel viewModel = new SummaryViewModel(conversationHandle(),
                    controlled.repository, main, System::currentTimeMillis);
            viewModel.request(SummaryScope.TODAY, null);
            main.drain();
            controlled.callbacks.get(0).onError(SummaryError.INVALID_TIME_ZONE);
            assertEquals("retry must run on the publication executor", 1, controlled.callbacks.size());

            viewModel.dismiss();
            main.drain();

            assertEquals(1, controlled.callbacks.size());
            assertEquals(SummaryUiState.Status.IDLE, viewModel.state().getValue().getStatus());
        }
    }

    @Test
    public void changedScopeRejectsQueuedSuccessAndErrorBeforePublishingTheCurrentResult()
            throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            ControlledRepository controlled = new ControlledRepository();
            QueuedExecutor main = new QueuedExecutor();
            SavedStateHandle saved = conversationHandle();
            SummaryViewModel viewModel = new SummaryViewModel(saved, controlled.repository, main,
                    System::currentTimeMillis);
            viewModel.request(SummaryScope.UNREAD, null);
            main.drain();
            controlled.callbacks.get(0).onSuccess(summaryResponse("old"));
            controlled.callbacks.get(0).onError(SummaryError.NETWORK);
            viewModel.request(SummaryScope.LAST_N, 20);
            controlled.callbacks.get(1).onSuccess(summaryResponse("current"));

            main.drain();

            assertEquals("current", viewModel.state().getValue().getSummary().getSummaryId());
            assertEquals(SummaryScope.LAST_N, viewModel.state().getValue().getScope());
            assertEquals("current", saved.get("summary_last_id"));
        }
    }

    @Test
    public void aChangedConversationRejectsAnOldQueuedSummary() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            ControlledRepository controlled = new ControlledRepository();
            QueuedExecutor main = new QueuedExecutor();
            SavedStateHandle saved = conversationHandle();
            SummaryViewModel viewModel = new SummaryViewModel(saved, controlled.repository, main,
                    System::currentTimeMillis);
            viewModel.request(SummaryScope.UNREAD, null);
            main.drain();
            controlled.callbacks.get(0).onSuccess(summaryResponse("old-conversation"));
            saved.set(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-2");

            main.drain();

            assertNull(saved.get("summary_last_id"));
            assertNull(viewModel.state().getValue().getSummary());
            viewModel.request(SummaryScope.UNREAD, null);
            assertEquals("conversation-2", controlled.conversations.get(1));
        }
    }

    @Test
    public void aHandleReturnedAfterReentrantDismissIsCanceled() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepository repository = mock(SummaryRepository.class);
            AtomicInteger canceled = new AtomicInteger();
            AtomicReference<SummaryViewModel> current = new AtomicReference<>();
            when(repository.request(anyString(), any(), any())).thenAnswer(invocation -> {
                current.get().dismiss();
                return (SummaryRepository.Cancellable) canceled::incrementAndGet;
            });
            SummaryViewModel viewModel = new SummaryViewModel(conversationHandle(), repository);
            current.set(viewModel);

            viewModel.request(SummaryScope.UNREAD, null);

            assertEquals(1, canceled.get());
            assertEquals(SummaryUiState.Status.IDLE, viewModel.state().getValue().getStatus());
        }
    }

    @Test
    public void changingConversationDropsThePreviousSavedSummaryId() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            ControlledRepository controlled = new ControlledRepository();
            SavedStateHandle saved = conversationHandle();
            SummaryViewModel viewModel = new SummaryViewModel(saved, controlled.repository);
            viewModel.request(SummaryScope.UNREAD, null);
            controlled.callbacks.get(0).onSuccess(summaryResponse("previous-chat"));
            assertEquals("previous-chat", saved.get("summary_last_id"));

            saved.set(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-2");
            viewModel.request(SummaryScope.TODAY, null);

            assertNull(saved.get("summary_last_id"));
            assertNull(viewModel.state().getValue().getLastSummaryId());
            assertNull(viewModel.state().getValue().getSummary());
        }
    }

    @Test
    public void loadingObserverDismissPreventsAnObsoleteSummaryRequest() {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            ControlledRepository controlled = new ControlledRepository();
            SummaryViewModel viewModel = new SummaryViewModel(conversationHandle(),
                    controlled.repository);
            viewModel.state().observeForever(value -> {
                if (value.getStatus() == SummaryUiState.Status.LOADING) {
                    viewModel.dismiss();
                }
            });

            viewModel.request(SummaryScope.UNREAD, null);

            assertTrue(controlled.callbacks.isEmpty());
            assertEquals(SummaryUiState.Status.IDLE, viewModel.state().getValue().getStatus());
        }
    }

    @Test
    public void queuedTimezoneRetryCannotSupersedeTheNewScope() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            ControlledRepository controlled = new ControlledRepository();
            QueuedExecutor main = new QueuedExecutor();
            SummaryViewModel viewModel = new SummaryViewModel(conversationHandle(),
                    controlled.repository, main, System::currentTimeMillis);
            viewModel.request(SummaryScope.TODAY, null);
            main.drain();
            controlled.callbacks.get(0).onError(SummaryError.INVALID_TIME_ZONE);
            viewModel.request(SummaryScope.LAST_N, 20);
            controlled.callbacks.get(1).onSuccess(summaryResponse("latest-scope"));

            main.drain();

            assertEquals(2, controlled.callbacks.size());
            assertEquals(SummaryScope.LAST_N, viewModel.state().getValue().getScope());
            assertEquals("latest-scope", viewModel.state().getValue().getSummary().getSummaryId());
        }
    }

    private static SavedStateHandle conversationHandle() {
        return new SavedStateHandle(Map.of(SummaryViewModel.ARG_CONVERSATION_ID, "conversation-1"));
    }

    @Test
    public void cacheClearRejectsNetworkSuccessAlreadyQueuedForPublication() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepositoryTest.ControlledApi api = new SummaryRepositoryTest.ControlledApi();
            SummaryRepository repository = new SummaryRepository(api);
            QueuedExecutor main = new QueuedExecutor();
            SavedStateHandle saved = conversationHandle();
            SummaryViewModel viewModel = new SummaryViewModel(saved, repository, main,
                    System::currentTimeMillis);
            viewModel.request(SummaryScope.UNREAD, null);
            main.drain();
            api.calls.get(0).succeed(summaryResponse("invalidated"));
            repository.clearCache();
            main.drain();

            assertEquals(SummaryUiState.Status.IDLE, viewModel.state().getValue().getStatus());
            assertNull(viewModel.state().getValue().getSummary());
            assertNull(saved.get("summary_last_id"));
        }
    }

    @Test
    public void cacheClearRejectsCachedSuccessAlreadyQueuedForPublication() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepositoryTest.ControlledApi api = new SummaryRepositoryTest.ControlledApi();
            SummaryRepository repository = new SummaryRepository(api);
            QueuedExecutor main = new QueuedExecutor();
            SavedStateHandle saved = conversationHandle();
            SummaryViewModel viewModel = new SummaryViewModel(saved, repository, main,
                    System::currentTimeMillis);
            viewModel.request(SummaryScope.UNREAD, null);
            api.calls.get(0).succeed(summaryResponse("cached-invalidated"));
            main.drain();
            viewModel.request(SummaryScope.UNREAD, null);
            assertEquals(1, api.calls.size());
            repository.clearCache();
            main.drain();

            assertEquals(SummaryUiState.Status.IDLE, viewModel.state().getValue().getStatus());
            assertNull(saved.get("summary_last_id"));
        }
    }

    @Test
    public void cacheClearRejectsQueuedTimezoneRetry() {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepositoryTest.ControlledApi api = new SummaryRepositoryTest.ControlledApi();
            SummaryRepository repository = new SummaryRepository(api);
            QueuedExecutor main = new QueuedExecutor();
            SummaryViewModel viewModel = new SummaryViewModel(conversationHandle(), repository,
                    main, System::currentTimeMillis);
            viewModel.request(SummaryScope.TODAY, null);
            main.drain();
            api.calls.get(0).error(400, "{\"error\":{\"code\":\"INVALID_TIME_ZONE\"}}");
            repository.clearCache();
            main.drain();

            assertEquals(1, api.calls.size());
            assertEquals(SummaryUiState.Status.IDLE, viewModel.state().getValue().getStatus());
        }
    }

    @Test
    public void cacheClearRemovesAlreadyPublishedReadyStateAndSavedId() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepositoryTest.ControlledApi api = new SummaryRepositoryTest.ControlledApi();
            SummaryRepository repository = new SummaryRepository(api);
            QueuedExecutor main = new QueuedExecutor();
            SavedStateHandle saved = conversationHandle();
            SummaryViewModel viewModel = new SummaryViewModel(saved, repository, main,
                    System::currentTimeMillis);
            viewModel.request(SummaryScope.UNREAD, null);
            api.calls.get(0).succeed(summaryResponse("ready-before-clear"));
            main.drain();
            assertEquals("ready-before-clear", saved.get("summary_last_id"));

            repository.clearCache();
            main.drain();

            assertEquals(SummaryUiState.Status.IDLE, viewModel.state().getValue().getStatus());
            assertNull(saved.get("summary_last_id"));
        }
    }

    @Test
    public void newRequestBeforeInvalidationCleanupCannotRetainOldSavedIdOrBeCanceled()
            throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepositoryTest.ControlledApi api = new SummaryRepositoryTest.ControlledApi();
            SummaryRepository repository = new SummaryRepository(api);
            QueuedExecutor main = new QueuedExecutor();
            SavedStateHandle saved = conversationHandle();
            SummaryViewModel viewModel = new SummaryViewModel(saved, repository, main,
                    System::currentTimeMillis);
            viewModel.request(SummaryScope.UNREAD, null);
            api.calls.get(0).succeed(summaryResponse("before-clear"));
            main.drain();
            repository.clearCache();

            viewModel.request(SummaryScope.UNREAD, null);
            assertNull(saved.get("summary_last_id"));
            api.calls.get(1).succeed(summaryResponse("after-clear"));
            main.drain();

            assertFalse(api.calls.get(1).isCanceled());
            assertEquals("after-clear", saved.get("summary_last_id"));
            assertEquals("after-clear", viewModel.state().getValue().getSummary().getSummaryId());
        }
    }

    @Test
    public void membershipInvalidationKeepsTheErrorAndRemovesTheSavedSummaryId() throws Exception {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepositoryTest.ControlledApi api = new SummaryRepositoryTest.ControlledApi();
            SummaryRepository repository = new SummaryRepository(api);
            QueuedExecutor main = new QueuedExecutor();
            SavedStateHandle saved = conversationHandle();
            SummaryViewModel viewModel = new SummaryViewModel(saved, repository, main,
                    System::currentTimeMillis);
            viewModel.request(SummaryScope.UNREAD, null);
            api.calls.get(0).succeed(summaryResponse("before-membership-loss"));
            main.drain();
            viewModel.refresh();
            api.calls.get(1).error(403, "{\"error\":{\"code\":\"NOT_MEMBER\"}}");
            main.drain();

            assertEquals(SummaryUiState.Status.ERROR, viewModel.state().getValue().getStatus());
            assertEquals(SummaryError.NOT_MEMBER, viewModel.state().getValue().getError());
            assertNull(saved.get("summary_last_id"));
        }
    }

    @Test
    public void clearingViewModelRejectsAlreadyQueuedCacheInvalidationCleanup() {
        try (LiveDataTestExecutor ignored = new LiveDataTestExecutor()) {
            SummaryRepositoryTest.ControlledApi api = new SummaryRepositoryTest.ControlledApi();
            SummaryRepository repository = new SummaryRepository(api);
            QueuedExecutor main = new QueuedExecutor();
            SummaryViewModel viewModel = new SummaryViewModel(conversationHandle(), repository,
                    main, System::currentTimeMillis);
            ViewModelStore store = new ViewModelStore();
            store.put("summary", viewModel);
            viewModel.request(SummaryScope.UNREAD, null);
            main.drain();
            repository.clearCache();

            store.clear();
            main.drain();
            assertEquals(SummaryUiState.Status.LOADING, viewModel.state().getValue().getStatus());
            repository.clearCache();
            main.drain();
            assertEquals(SummaryUiState.Status.LOADING, viewModel.state().getValue().getStatus());
        }
    }

    private static final class ControlledRepository {
        private final SummaryRepository repository = mock(SummaryRepository.class);
        private final List<SummaryRepository.Callback> callbacks = new ArrayList<>();
        private final List<String> conversations = new ArrayList<>();
        private final AtomicInteger canceled = new AtomicInteger();

        private ControlledRepository() {
            when(repository.request(anyString(), any(), any())).thenAnswer(invocation -> {
                conversations.add(invocation.getArgument(0));
                callbacks.add(invocation.getArgument(2));
                return (SummaryRepository.Cancellable) canceled::incrementAndGet;
            });
        }
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

    private static SummaryResponse summaryResponse(String summaryId) throws Exception {
        return new Moshi.Builder().build().adapter(SummaryResponse.class).fromJson(
                SummaryRepositoryTest.responseJson(summaryId, false));
    }

    private static SummaryResponse todaySummary(String summaryId, String date, String zone)
            throws Exception {
        return todaySummary(summaryId, date, zone, false);
    }

    private static SummaryResponse todaySummary(
            String summaryId, String date, String zone, boolean zoneDefaulted) throws Exception {
        String period = "{\"date\":\"" + date + "\",\"timeZone\":\"" + zone + "\","
                + "\"start\":\"2026-11-17T00:00:00Z\",\"end\":\"2026-11-18T00:00:00Z\","
                + "\"timeZoneDefaulted\":" + zoneDefaulted + "}";
        return new Moshi.Builder().build().adapter(SummaryResponse.class).fromJson(
                SummaryRepositoryTest.responseJson(summaryId, false, "TODAY", null, period));
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
