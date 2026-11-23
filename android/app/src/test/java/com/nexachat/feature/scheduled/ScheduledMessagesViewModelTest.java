package com.nexachat.feature.scheduled;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.network.stomp.ChatEvent;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class ScheduledMessagesViewModelTest {

    private static final String CONVERSATION_ID = "conversation-1";
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Instant SOON = Instant.parse("2026-11-09T10:05:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    private FakeEventSource eventSource;

    @Before
    public void setUp() {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        eventSource = new FakeEventSource();
    }

    @After
    public void tearDown() {
        RxAndroidPlugins.reset();
    }

    @Test
    public void loadRendersRowsAndSurfacesLoadFailures() {
        FakeRepository repository = new FakeRepository();
        repository.nextList = List.of(message("s-1", "Sáng mai", SOON,
                ScheduledMessageStatus.PENDING));
        ScheduledMessagesViewModel viewModel = createViewModel(repository);

        viewModel.load();

        ScheduledMessageUiState state = viewModel.getUiState().getValue();
        assertTrue(state.isLoaded());
        assertFalse(state.isLoading());
        assertEquals(1, state.getMessages().size());
        assertEquals("Sáng mai", state.getMessages().get(0).getBody());

        FakeRepository failing = new FakeRepository();
        failing.loadFailure = ScheduledMessageRepository.Failure.OFFLINE;
        ScheduledMessagesViewModel failingViewModel = createViewModel(failing);
        failingViewModel.load();
        assertEquals(ScheduledMessageRepository.Failure.OFFLINE,
                failingViewModel.getUiState().getValue().getLoadError());
    }

    @Test
    public void rejectsInvalidDraftsBeforeCallingTheApi() {
        FakeRepository repository = new FakeRepository();
        ScheduledMessagesViewModel viewModel = createViewModel(repository);
        viewModel.load();

        viewModel.schedule("   ", SOON);
        assertEquals(ScheduledMessageEditorError.BODY_REQUIRED,
                viewModel.getUiState().getValue().getEditorError());

        viewModel.schedule("a".repeat(10_001), SOON);
        assertEquals(ScheduledMessageEditorError.BODY_TOO_LONG,
                viewModel.getUiState().getValue().getEditorError());

        viewModel.schedule("Họp", null);
        assertEquals(ScheduledMessageEditorError.TIME_REQUIRED,
                viewModel.getUiState().getValue().getEditorError());

        viewModel.schedule("Họp", NOW.plusSeconds(30));
        assertEquals(ScheduledMessageEditorError.TIME_TOO_SOON,
                viewModel.getUiState().getValue().getEditorError());

        viewModel.schedule("Họp", NOW.plusSeconds(59));
        assertEquals(ScheduledMessageEditorError.TIME_TOO_SOON,
                viewModel.getUiState().getValue().getEditorError());
        assertTrue(repository.creates.isEmpty());

        viewModel.schedule("Họp", NOW.plusSeconds(60));
        assertEquals(1, repository.creates.size());
        assertNull(viewModel.getUiState().getValue().getEditorError());
    }

    @Test
    public void reusesTheClientMessageIdOnlyWhileThePayloadIsUnchanged() {
        FakeRepository repository = new FakeRepository();
        ScheduledMessagesViewModel viewModel = createViewModel(repository);
        viewModel.load();

        viewModel.schedule("  Họp team  ", SOON);
        PendingCreate first = repository.creates.get(0);
        assertEquals("Họp team", first.draft.normalized().getBody());
        assertNotNull(UUID.fromString(first.clientMessageId));

        first.fail(ScheduledMessageRepository.Failure.OFFLINE);
        assertEquals(ScheduledMessageEditorError.OFFLINE,
                viewModel.getUiState().getValue().getEditorError());

        viewModel.schedule("Họp team", SOON);
        PendingCreate retry = repository.creates.get(1);
        assertEquals(first.clientMessageId, retry.clientMessageId);

        retry.fail(ScheduledMessageRepository.Failure.OFFLINE);
        viewModel.schedule("Họp team 2", SOON);
        PendingCreate changed = repository.creates.get(2);
        assertNotEquals(first.clientMessageId, changed.clientMessageId);

        changed.succeed(message("s-1", "Họp team 2", SOON,
                ScheduledMessageStatus.PENDING));
        ScheduledMessageUiState state = viewModel.getUiState().getValue();
        assertEquals(1, state.getMessages().size());
        assertEquals(1L, state.getSavedSequence());
        assertEquals("s-1", state.getScheduledMessage().getId());
        assertNull(state.getEditorError());
    }

    @Test
    public void scheduleInsertsTheEchoedRowInSendAtOrder() {
        FakeRepository repository = new FakeRepository();
        repository.nextList = List.of(message("s-late", "later",
                SOON.plusSeconds(600), ScheduledMessageStatus.PENDING));
        ScheduledMessagesViewModel viewModel = createViewModel(repository);
        viewModel.load();

        viewModel.schedule("early", SOON);
        repository.creates.get(0).succeed(message("s-early", "early", SOON,
                ScheduledMessageStatus.PENDING));

        List<ScheduledMessage> rows = viewModel.getUiState().getValue().getMessages();
        assertEquals(2, rows.size());
        assertEquals("s-early", rows.get(0).getId());
        assertEquals("s-late", rows.get(1).getId());
    }

    @Test
    public void cancelDeletesPendingRowsAndSkipsTerminalOnes() {
        FakeRepository repository = new FakeRepository();
        repository.nextList = List.of(
                message("s-1", "pending", SOON, ScheduledMessageStatus.PENDING),
                message("s-2", "sent", SOON, ScheduledMessageStatus.SENT));
        ScheduledMessagesViewModel viewModel = createViewModel(repository);
        viewModel.load();

        viewModel.cancelScheduled("s-2");
        assertTrue(repository.deletes.isEmpty());
        assertEquals(ScheduledMessageEditorError.DISPATCHING,
                viewModel.getEvents().getValue().consume());

        viewModel.cancelScheduled("s-missing");
        assertEquals(ScheduledMessageEditorError.NOT_FOUND,
                viewModel.getEvents().getValue().consume());
        assertTrue(repository.deletes.isEmpty());

        viewModel.cancelScheduled("s-1");
        assertEquals(1, repository.deletes.size());
        repository.deletes.get(0).succeed();
        assertEquals(1, viewModel.getUiState().getValue().getMessages().size());
        assertEquals("s-2", viewModel.getUiState().getValue().getMessages().get(0).getId());
    }

    @Test
    public void dispatchingDeleteEmitsTheNoticeAndRefreshesTheList() {
        FakeRepository repository = new FakeRepository();
        repository.nextList = List.of(message("s-1", "going", SOON,
                ScheduledMessageStatus.PENDING));
        ScheduledMessagesViewModel viewModel = createViewModel(repository);
        viewModel.load();
        assertEquals(1, repository.loads);

        viewModel.cancelScheduled("s-1");
        repository.deletes.get(0).fail(ScheduledMessageRepository.Failure.DISPATCHING);

        assertEquals(ScheduledMessageEditorError.DISPATCHING,
                viewModel.getEvents().getValue().consume());
        assertEquals(2, repository.loads);
    }

    @Test
    public void silentRefreshFailureKeepsRowsAndRecoversOnTheNextRefresh() {
        FakeRepository repository = new FakeRepository();
        repository.nextList = List.of(message("s-1", "row", SOON,
                ScheduledMessageStatus.PENDING));
        ScheduledMessagesViewModel viewModel = createViewModel(repository);
        viewModel.load();
        assertEquals(1, viewModel.getUiState().getValue().getMessages().size());

        repository.loadFailure = ScheduledMessageRepository.Failure.OFFLINE;
        viewModel.refresh();
        ScheduledMessageUiState failed = viewModel.getUiState().getValue();
        assertEquals(ScheduledMessageRepository.Failure.OFFLINE, failed.getRefreshError());
        assertEquals(1, failed.getMessages().size());
        assertNull(failed.getLoadError());

        repository.loadFailure = null;
        viewModel.refresh();
        ScheduledMessageUiState recovered = viewModel.getUiState().getValue();
        assertNull(recovered.getRefreshError());
        assertEquals(1, recovered.getMessages().size());
    }

    @Test
    public void aTerminalPushRefreshesOnlyTheMatchingConversation() {
        FakeRepository repository = new FakeRepository();
        ScheduledMessagesViewModel viewModel = createViewModel(repository);
        viewModel.load();
        assertEquals(1, repository.loads);

        eventSource.push("another-conversation");
        assertEquals(1, repository.loads);

        eventSource.push(CONVERSATION_ID);
        assertEquals(2, repository.loads);
    }

    @Test
    public void conflictFailuresClearTheReusedClientMessageId() {
        FakeRepository repository = new FakeRepository();
        ScheduledMessagesViewModel viewModel = createViewModel(repository);
        viewModel.load();

        viewModel.schedule("Họp", SOON);
        String firstId = repository.creates.get(0).clientMessageId;
        repository.creates.get(0).fail(ScheduledMessageRepository.Failure.CONFLICT);
        assertEquals(ScheduledMessageEditorError.CONFLICT,
                viewModel.getUiState().getValue().getEditorError());

        viewModel.schedule("Họp", SOON);
        assertNotEquals(firstId, repository.creates.get(1).clientMessageId);
    }

    private ScheduledMessagesViewModel createViewModel(FakeRepository repository) {
        SavedStateHandle savedState = new SavedStateHandle(
                Map.of(ScheduledMessagesViewModel.ARG_CONVERSATION_ID, CONVERSATION_ID));
        return new ScheduledMessagesViewModel(savedState, repository, CLOCK, eventSource);
    }

    private static ScheduledMessage message(
            String id, String body, Instant sendAt, ScheduledMessageStatus status) {
        return new ScheduledMessage(
                id, CONVERSATION_ID, "cm-" + id, body, sendAt, status,
                status == ScheduledMessageStatus.FAILED ? "VALIDATION_FAILED" : null,
                status == ScheduledMessageStatus.SENT ? "m-1" : null, NOW, NOW);
    }

    private static final class FakeEventSource implements ScheduledMessageEventSource {
        private final PublishProcessor<ChatEvent.ScheduledMessageUpdated> updates =
                PublishProcessor.create();

        @Override
        public Flowable<ChatEvent.ScheduledMessageUpdated> updates() {
            return updates;
        }

        void push(String conversationId) {
            updates.onNext(new ChatEvent.ScheduledMessageUpdated(
                    conversationId, "s-1", "cm-1", "SENT", null, null, "m-9"));
        }
    }

    private static final class FakeRepository implements ScheduledMessageRepository {
        private List<ScheduledMessage> nextList = List.of();
        private Failure loadFailure;
        private int loads;
        private final List<PendingCreate> creates = new ArrayList<>();
        private final List<PendingDelete> deletes = new ArrayList<>();

        @Override
        public Cancellable load(String conversationId, ListCallback callback) {
            loads++;
            if (loadFailure != null) {
                callback.onFailure(loadFailure);
            } else {
                callback.onSuccess(nextList);
            }
            return () -> { };
        }

        @Override
        public Cancellable create(
                String conversationId,
                ScheduledMessageDraft draft,
                String clientMessageId,
                ItemCallback callback) {
            PendingCreate pending = new PendingCreate(draft, clientMessageId, callback);
            creates.add(pending);
            return pending;
        }

        @Override
        public Cancellable delete(
                String conversationId, String scheduledId, DeleteCallback callback) {
            PendingDelete pending = new PendingDelete(callback);
            deletes.add(pending);
            return pending;
        }
    }

    private static final class PendingCreate implements ScheduledMessageRepository.Cancellable {
        private final ScheduledMessageDraft draft;
        private final String clientMessageId;
        private final ScheduledMessageRepository.ItemCallback callback;

        PendingCreate(
                ScheduledMessageDraft draft, String clientMessageId,
                ScheduledMessageRepository.ItemCallback callback) {
            this.draft = draft;
            this.clientMessageId = clientMessageId;
            this.callback = callback;
        }

        void succeed(ScheduledMessage message) {
            callback.onSuccess(message);
        }

        void fail(ScheduledMessageRepository.Failure failure) {
            callback.onFailure(failure);
        }

        @Override
        public void cancel() { }
    }

    private static final class PendingDelete implements ScheduledMessageRepository.Cancellable {
        private final ScheduledMessageRepository.DeleteCallback callback;

        PendingDelete(ScheduledMessageRepository.DeleteCallback callback) {
            this.callback = callback;
        }

        void succeed() {
            callback.onSuccess();
        }

        void fail(ScheduledMessageRepository.Failure failure) {
            callback.onFailure(failure);
        }

        @Override
        public void cancel() { }
    }
}
