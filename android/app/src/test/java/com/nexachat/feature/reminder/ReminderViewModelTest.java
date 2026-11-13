package com.nexachat.feature.reminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
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

public final class ReminderViewModelTest {

    private static final String CONVERSATION_ID = "conversation-1";
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Instant SOON = Instant.parse("2026-12-01T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    private ReminderPushEvents pushEvents;

    @Before
    public void setUp() {
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        pushEvents = new ReminderPushEvents();
    }

    @After
    public void tearDown() {
        RxAndroidPlugins.reset();
    }

    @Test
    public void loadRendersRemindersAndSurfacesLoadFailures() {
        FakeRepository repository = new FakeRepository();
        repository.nextList = List.of(reminder("r-1", "Họp team", SOON, true));
        ReminderViewModel viewModel = createViewModel(repository);

        viewModel.load();

        ReminderUiState state = viewModel.getUiState().getValue();
        assertTrue(state.isLoaded());
        assertFalse(state.isLoading());
        assertEquals(1, state.getReminders().size());
        assertEquals("Họp team", state.getReminders().get(0).getTitle());

        FakeRepository failing = new FakeRepository();
        failing.loadFailure = ReminderRepository.Failure.OFFLINE;
        ReminderViewModel failingViewModel = createViewModel(failing);
        failingViewModel.load();
        assertEquals(ReminderRepository.Failure.OFFLINE,
                failingViewModel.getUiState().getValue().getLoadError());
    }

    @Test
    public void rejectsInvalidDraftsBeforeCallingTheApi() {
        FakeRepository repository = new FakeRepository();
        ReminderViewModel viewModel = createViewModel(repository);
        viewModel.load();

        viewModel.saveReminder(null, "   ", SOON, ReminderRepeat.NONE);
        assertEquals(ReminderEditorError.TITLE_REQUIRED,
                viewModel.getUiState().getValue().getEditorError());

        viewModel.saveReminder(null, "a".repeat(201), SOON, ReminderRepeat.NONE);
        assertEquals(ReminderEditorError.TITLE_TOO_LONG,
                viewModel.getUiState().getValue().getEditorError());

        viewModel.saveReminder(null, "Họp", NOW.minusSeconds(1), ReminderRepeat.NONE);
        assertEquals(ReminderEditorError.TIME_NOT_FUTURE,
                viewModel.getUiState().getValue().getEditorError());

        viewModel.saveReminder(null, "Họp", NOW.plus(ReminderTime.MAX_LEAD_TIME).plusSeconds(1),
                ReminderRepeat.NONE);
        assertEquals(ReminderEditorError.TIME_TOO_FAR,
                viewModel.getUiState().getValue().getEditorError());
        assertTrue(repository.creates.isEmpty());

        viewModel.saveReminder(null, "Họp", SOON, null);
        assertEquals(1, repository.creates.size());
        assertEquals(ReminderRepeat.NONE, repository.creates.get(0).draft.getRepeat());
    }

    @Test
    public void reusesTheIdempotencyKeyOnlyWhileThePayloadIsUnchanged() {
        FakeRepository repository = new FakeRepository();
        ReminderViewModel viewModel = createViewModel(repository);
        viewModel.load();

        viewModel.saveReminder(null, "  Họp team  ", SOON, ReminderRepeat.WEEKLY);
        PendingCreate first = repository.creates.get(0);
        assertEquals("Họp team", first.draft.getTitle());
        assertEquals(ReminderRepeat.WEEKLY, first.draft.getRepeat());
        assertNotNull(UUID.fromString(first.idempotencyKey));

        first.fail(ReminderRepository.Failure.OFFLINE);
        assertEquals(ReminderEditorError.OFFLINE,
                viewModel.getUiState().getValue().getEditorError());

        viewModel.saveReminder(null, "Họp team", SOON, ReminderRepeat.WEEKLY);
        PendingCreate retry = repository.creates.get(1);
        assertEquals(first.idempotencyKey, retry.idempotencyKey);

        retry.fail(ReminderRepository.Failure.OFFLINE);
        viewModel.saveReminder(null, "Họp team 2", SOON, ReminderRepeat.WEEKLY);
        PendingCreate changed = repository.creates.get(2);
        assertNotEquals(first.idempotencyKey, changed.idempotencyKey);

        changed.succeed(reminder("r-1", "Họp team 2", SOON, true));
        ReminderUiState state = viewModel.getUiState().getValue();
        assertEquals(1, state.getReminders().size());
        assertEquals(1L, state.getSavedSequence());
        assertNull(state.getEditorError());
    }

    @Test
    public void editingRequiresTheServerCanEditFlag() {
        FakeRepository repository = new FakeRepository();
        repository.nextList = List.of(reminder("r-1", "Họp", SOON, false));
        ReminderViewModel viewModel = createViewModel(repository);
        viewModel.load();

        viewModel.saveReminder("r-1", "Đổi giờ", SOON.plusSeconds(3600),
                ReminderRepeat.DAILY);

        assertEquals(ReminderEditorError.FORBIDDEN,
                viewModel.getUiState().getValue().getEditorError());
        assertTrue(repository.updates.isEmpty());

        FakeRepository editable = new FakeRepository();
        editable.nextList = List.of(reminder("r-1", "Họp", SOON, true));
        ReminderViewModel editingViewModel = createViewModel(editable);
        editingViewModel.load();
        editingViewModel.saveReminder("r-1", "Đổi giờ", SOON, ReminderRepeat.DAILY);

        assertEquals(1, editable.updates.size());
        assertEquals("Đổi giờ", editable.updates.get(0).title);
        assertEquals(ReminderRepeat.DAILY, editable.updates.get(0).repeat);
        editable.updates.get(0).succeed(reminder("r-1", "Đổi giờ", SOON, true));
        assertEquals("Đổi giờ",
                editingViewModel.getUiState().getValue().getReminders().get(0).getTitle());
    }

    @Test
    public void deleteRequiresCanEditAndReportsFailuresAsOneShotEvents() {
        FakeRepository repository = new FakeRepository();
        repository.nextList = List.of(reminder("r-1", "Họp", SOON, false));
        ReminderViewModel viewModel = createViewModel(repository);
        viewModel.load();
        viewModel.deleteReminder("r-1");
        assertNotNull(viewModel.getEvents().getValue());
        assertEquals(ReminderEditorError.FORBIDDEN,
                viewModel.getEvents().getValue().consume());
        assertTrue(repository.deletes.isEmpty());

        FakeRepository editable = new FakeRepository();
        editable.nextList = List.of(reminder("r-1", "Họp", SOON, true));
        ReminderViewModel deleting = createViewModel(editable);
        deleting.load();
        deleting.deleteReminder("r-1");
        assertEquals(1, editable.deletes.size());
        editable.deletes.get(0).succeed();
        assertTrue(deleting.getUiState().getValue().getReminders().isEmpty());

        FakeRepository failing = new FakeRepository();
        failing.nextList = List.of(reminder("r-1", "Họp", SOON, true));
        ReminderViewModel failingViewModel = createViewModel(failing);
        failingViewModel.load();
        failingViewModel.deleteReminder("r-1");
        failing.deletes.get(0).fail(ReminderRepository.Failure.NOT_FOUND);
        assertEquals(ReminderEditorError.NOT_FOUND,
                failingViewModel.getEvents().getValue().consume());
    }

    @Test
    public void silentRefreshFailureKeepsRemindersAndRecoversOnTheNextRefresh() {
        FakeRepository repository = new FakeRepository();
        repository.nextList = List.of(reminder("r-1", "Họp team", SOON, true));
        ReminderViewModel viewModel = createViewModel(repository);
        viewModel.load();
        assertEquals(1, viewModel.getUiState().getValue().getReminders().size());

        repository.loadFailure = ReminderRepository.Failure.OFFLINE;
        viewModel.refresh();
        ReminderUiState failed = viewModel.getUiState().getValue();
        assertEquals(ReminderRepository.Failure.OFFLINE, failed.getRefreshError());
        assertEquals(1, failed.getReminders().size());
        assertNull(failed.getLoadError());

        int loads = repository.loads;
        repository.loadFailure = null;
        viewModel.refresh();
        assertEquals(loads + 1, repository.loads);
        ReminderUiState recovered = viewModel.getUiState().getValue();
        assertNull(recovered.getRefreshError());
        assertEquals(1, recovered.getReminders().size());
    }

    @Test
    public void aReminderPushRefreshesOnlyTheMatchingConversation() {
        FakeRepository repository = new FakeRepository();
        ReminderViewModel viewModel = createViewModel(repository);
        viewModel.load();
        assertEquals(1, repository.loads);

        pushEvents.onReminderPush("another-conversation");
        assertEquals(1, repository.loads);

        pushEvents.onReminderPush(CONVERSATION_ID);
        assertEquals(2, repository.loads);
    }

    private ReminderViewModel createViewModel(FakeRepository repository) {
        SavedStateHandle savedState = new SavedStateHandle(
                Map.of(ReminderViewModel.ARG_CONVERSATION_ID, CONVERSATION_ID));
        return new ReminderViewModel(savedState, repository, CLOCK, pushEvents);
    }

    private static Reminder reminder(
            String id, String title, Instant remindAt, boolean canEdit) {
        return new Reminder(
                id, CONVERSATION_ID, title, remindAt, "user-1", "Binh",
                ReminderRepeat.NONE, canEdit, NOW, NOW);
    }

    private static final class FakeRepository implements ReminderRepository {
        private List<Reminder> nextList = List.of();
        private Failure loadFailure;
        private int loads;
        private final List<PendingCreate> creates = new ArrayList<>();
        private final List<PendingUpdate> updates = new ArrayList<>();
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
                ReminderDraft draft,
                String idempotencyKey,
                ItemCallback callback) {
            PendingCreate pending = new PendingCreate(draft, idempotencyKey, callback);
            creates.add(pending);
            return pending;
        }

        @Override
        public Cancellable update(
                String conversationId,
                String reminderId,
                String title,
                Instant remindAt,
                ReminderRepeat repeat,
                ItemCallback callback) {
            PendingUpdate pending = new PendingUpdate(title, repeat, callback);
            updates.add(pending);
            return pending;
        }

        @Override
        public Cancellable delete(
                String conversationId, String reminderId, DeleteCallback callback) {
            PendingDelete pending = new PendingDelete(callback);
            deletes.add(pending);
            return pending;
        }
    }

    private static final class PendingCreate implements ReminderRepository.Cancellable {
        private final ReminderDraft draft;
        private final String idempotencyKey;
        private final ReminderRepository.ItemCallback callback;

        PendingCreate(
                ReminderDraft draft, String idempotencyKey,
                ReminderRepository.ItemCallback callback) {
            this.draft = draft;
            this.idempotencyKey = idempotencyKey;
            this.callback = callback;
        }

        void succeed(Reminder reminder) {
            callback.onSuccess(reminder);
        }

        void fail(ReminderRepository.Failure failure) {
            callback.onFailure(failure);
        }

        @Override
        public void cancel() { }
    }

    private static final class PendingUpdate implements ReminderRepository.Cancellable {
        private final String title;
        private final ReminderRepeat repeat;
        private final ReminderRepository.ItemCallback callback;

        PendingUpdate(
                String title, ReminderRepeat repeat, ReminderRepository.ItemCallback callback) {
            this.title = title;
            this.repeat = repeat;
            this.callback = callback;
        }

        void succeed(Reminder reminder) {
            callback.onSuccess(reminder);
        }

        void fail(ReminderRepository.Failure failure) {
            callback.onFailure(failure);
        }

        @Override
        public void cancel() { }
    }

    private static final class PendingDelete implements ReminderRepository.Cancellable {
        private final ReminderRepository.DeleteCallback callback;

        PendingDelete(ReminderRepository.DeleteCallback callback) {
            this.callback = callback;
        }

        void succeed() {
            callback.onSuccess();
        }

        void fail(ReminderRepository.Failure failure) {
            callback.onFailure(failure);
        }

        @Override
        public void cancel() { }
    }
}
