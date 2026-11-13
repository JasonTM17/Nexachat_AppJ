package com.nexachat.feature.poll;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public final class PollViewModelTest {

    private static final String CONVERSATION_ID = "conversation-1";
    private static final String ME = "me";
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    @Test
    public void loadRendersTheFirstPageAndServerSelection() {
        FakeRepository repository = new FakeRepository();
        repository.nextPage = new PollPage(List.of(
                poll("p-1", "other", PollStatus.OPEN, null,
                        List.of(option("o-1", "A", 2, List.of()), option("o-2", "B", 0, List.of())),
                        List.of("o-1"), 1L)), null);
        PollViewModel viewModel = createViewModel(repository, true, viewer(false));

        viewModel.load();

        PollUiState state = viewModel.getUiState().getValue();
        assertTrue(state.isLoaded());
        assertEquals(1, state.getPolls().size());
        assertEquals(List.of("o-1"), state.selection("p-1"));
        assertFalse(state.hasMore());
        assertEquals(1, repository.loads);
    }

    @Test
    public void singleChoiceSelectionReplacesAndAVoteIsOptimisticThenConfirmed() {
        FakeRepository repository = new FakeRepository();
        repository.nextPage = new PollPage(List.of(
                poll("p-1", "me", PollStatus.OPEN, null,
                        List.of(option("o-1", "A", 2, List.of()),
                                option("o-2", "B", 0, List.of())),
                        List.of(), 1L)), null);
        PollViewModel viewModel = createViewModel(repository, true, viewer(false));
        viewModel.load();

        viewModel.toggleOption("p-1", "o-1");
        assertEquals(List.of("o-1"), viewModel.getUiState().getValue().selection("p-1"));
        viewModel.toggleOption("p-1", "o-2");
        assertEquals(List.of("o-2"), viewModel.getUiState().getValue().selection("p-1"));

        viewModel.submitVote("p-1");
        PollUiState optimistic = viewModel.getUiState().getValue();
        assertEquals("p-1", optimistic.getVotingPollId());
        Poll optimisticPoll = optimistic.find("p-1");
        assertNotNull(optimisticPoll);
        assertEquals(1, optimisticPoll.getOptions().get(1).getVoteCount());
        assertEquals(List.of("o-2"), optimisticPoll.getMyOptionIds());
        assertEquals(1, repository.votes.size());
        assertEquals(List.of("o-2"), repository.votes.get(0).optionIds);

        repository.votes.get(0).succeed(poll("p-1", "me", PollStatus.OPEN, null,
                List.of(option("o-1", "A", 2, List.of()),
                        option("o-2", "B", 1, List.of(ME))),
                List.of("o-2"), 2L));

        PollUiState confirmed = viewModel.getUiState().getValue();
        assertNull(confirmed.getVotingPollId());
        assertEquals(List.of("o-2"), confirmed.selection("p-1"));
        assertEquals(1, confirmed.find("p-1").getOptions().get(1).getVoteCount());
    }

    @Test
    public void aFailedVoteRollsBackCountsAndTheLocalDraft() {
        FakeRepository repository = new FakeRepository();
        repository.nextPage = new PollPage(List.of(
                poll("p-1", "other", PollStatus.OPEN, null,
                        List.of(option("o-1", "A", 2, List.of()),
                                option("o-2", "B", 0, List.of())),
                        List.of(), 1L)), null);
        PollViewModel viewModel = createViewModel(repository, true, viewer(false));
        viewModel.load();
        viewModel.toggleOption("p-1", "o-1");
        viewModel.submitVote("p-1");
        assertEquals(3, viewModel.getUiState().getValue()
                .find("p-1").getOptions().get(0).getVoteCount());

        repository.votes.get(0).fail(PollRepository.Failure.OFFLINE);

        PollUiState rolledBack = viewModel.getUiState().getValue();
        assertEquals(2, rolledBack.find("p-1").getOptions().get(0).getVoteCount());
        assertTrue(rolledBack.selection("p-1").isEmpty());
        assertNull(rolledBack.getVotingPollId());
        assertEquals(PollActionError.OFFLINE,
                viewModel.getEvents().getValue().consume());
    }

    @Test
    public void anonymousPollsNeverExposeVoterIdsEvenIfThePayloadLeaksThem() {
        FakeRepository repository = new FakeRepository();
        repository.nextPage = new PollPage(List.of(
                poll("p-1", "other", PollStatus.OPEN, null, true,
                        List.of(option("o-1", "A", 1, List.of(ME)),
                                option("o-2", "B", 0, List.of())),
                        List.of(), 1L)), null);
        PollViewModel viewModel = createViewModel(repository, true, viewer(false));
        viewModel.load();

        PollUiState state = viewModel.getUiState().getValue();
        Poll poll = state.find("p-1");
        assertNotNull(poll);
        assertTrue(poll.getOptions().get(0).getVoterIds().isEmpty());

        viewModel.toggleOption("p-1", "o-1");
        viewModel.submitVote("p-1");
        assertTrue(viewModel.getUiState().getValue()
                .find("p-1").getOptions().get(0).getVoterIds().isEmpty());
    }

    @Test
    public void closedAndExpiredPollsRejectVotesWithoutANetworkCall() {
        FakeRepository repository = new FakeRepository();
        repository.nextPage = new PollPage(List.of(
                poll("p-1", "other", PollStatus.CLOSED, null,
                        List.of(option("o-1", "A", 1, List.of()),
                                option("o-2", "B", 0, List.of())),
                        List.of(), 1L)), null);
        PollViewModel viewModel = createViewModel(repository, true, viewer(false));
        viewModel.load();

        viewModel.toggleOption("p-1", "o-1");
        assertEquals(PollActionError.CLOSED,
                viewModel.getEvents().getValue().consume());
        assertTrue(viewModel.getUiState().getValue().selection("p-1").isEmpty());

        FakeRepository expiredRepository = new FakeRepository();
        expiredRepository.nextPage = new PollPage(List.of(
                poll("p-2", "other", PollStatus.OPEN, NOW.minusSeconds(1),
                        List.of(option("o-1", "A", 0, List.of()),
                                option("o-2", "B", 0, List.of())),
                        List.of(), 1L)), null);
        PollViewModel expired = createViewModel(expiredRepository, true, viewer(false));
        expired.load();
        expired.submitVote("p-2");
        assertEquals(PollActionError.CLOSED, expired.getEvents().getValue().consume());
        assertTrue(expiredRepository.votes.isEmpty());
    }

    @Test
    public void aServerClosedRejectionMarksThePollClosedLocallyAndRollsBack() {
        FakeRepository repository = new FakeRepository();
        repository.nextPage = new PollPage(List.of(
                poll("p-1", "other", PollStatus.OPEN, null,
                        List.of(option("o-1", "A", 2, List.of()),
                                option("o-2", "B", 0, List.of())),
                        List.of(), 1L)), null);
        PollViewModel viewModel = createViewModel(repository, true, viewer(false));
        viewModel.load();
        viewModel.toggleOption("p-1", "o-1");
        viewModel.submitVote("p-1");
        repository.votes.get(0).fail(PollRepository.Failure.CLOSED);

        PollUiState state = viewModel.getUiState().getValue();
        assertEquals(PollStatus.CLOSED, state.find("p-1").getStatus());
        assertEquals(2, state.find("p-1").getOptions().get(0).getVoteCount());
        assertTrue(state.selection("p-1").isEmpty());
        assertEquals(PollActionError.CLOSED, viewModel.getEvents().getValue().consume());
    }

    @Test
    public void cachedChangeRefreshFailureKeepsPollsAndRecoversOnTheNextRefresh() {
        FakeRepository repository = new FakeRepository();
        repository.nextPage = new PollPage(List.of(
                poll("p-1", "other", PollStatus.OPEN, null,
                        List.of(option("o-1", "A", 0, List.of()),
                                option("o-2", "B", 0, List.of())),
                        List.of(), 1L)), null);
        PollViewModel viewModel = createViewModel(repository, true, viewer(false));
        viewModel.load();

        viewModel.refreshChanges();
        repository.changes.get(0).fail(PollRepository.Failure.OFFLINE);

        PollUiState failed = viewModel.getUiState().getValue();
        assertEquals(PollRepository.Failure.OFFLINE, failed.getRefreshError());
        assertEquals(1, failed.getPolls().size());
        assertNull(failed.getLoadError());

        int changesBefore = repository.changes.size();
        viewModel.refreshChanges();
        assertEquals(changesBefore + 1, repository.changes.size());
        repository.changes.get(changesBefore).succeed(new PollChanges(List.of(), 1L, false));
        assertNull(viewModel.getUiState().getValue().getRefreshError());

        viewModel.refreshChanges();
        repository.changes.get(changesBefore + 1).fail(PollRepository.Failure.OFFLINE);
        assertEquals(PollRepository.Failure.OFFLINE, viewModel.getUiState().getValue().getRefreshError());
        int beforeRetry = repository.changes.size();
        viewModel.retryRefresh();
        assertEquals(beforeRetry + 1, repository.changes.size());
        assertNull(viewModel.getUiState().getValue().getRefreshError());
    }

    @Test
    public void changeFeedMergesUpdatesAndDeleteTombstones() {
        FakeRepository repository = new FakeRepository();
        repository.nextPage = new PollPage(List.of(
                poll("p-1", "other", PollStatus.OPEN, null,
                        List.of(option("o-1", "A", 0, List.of()),
                                option("o-2", "B", 0, List.of())),
                        List.of(), 1L)), null);
        PollViewModel viewModel = createViewModel(repository, true, viewer(false));
        viewModel.load();

        viewModel.refreshChanges();
        assertEquals(1L, repository.changes.get(0).since);
        Poll updated = poll("p-1", "other", PollStatus.OPEN, null,
                List.of(option("o-1", "A", 3, List.of()),
                        option("o-2", "B", 1, List.of())),
                List.of(), 5L);
        repository.changes.get(0).succeed(new PollChanges(
                List.of(new PollChanges.Change("p-1", 5L, false, updated)), 5L, false));

        assertEquals(3, viewModel.getUiState().getValue()
                .find("p-1").getOptions().get(0).getVoteCount());
        viewModel.refreshChanges();
        assertEquals(5L, repository.changes.get(1).since);

        repository.changes.get(1).succeed(new PollChanges(
                List.of(new PollChanges.Change("p-1", 6L, true, null)), 6L, false));
        assertTrue(viewModel.getUiState().getValue().getPolls().isEmpty());
    }

    @Test
    public void creatorAndAdminMayManageAPoll() {
        FakeRepository repository = new FakeRepository();
        repository.nextPage = new PollPage(List.of(
                poll("p-1", "other", PollStatus.OPEN, null,
                        List.of(option("o-1", "A", 0, List.of()),
                                option("o-2", "B", 0, List.of())),
                        List.of(), 1L)), null);
        PollViewModel admin = createViewModel(repository, true, viewer(true));
        admin.load();
        assertTrue(admin.getUiState().getValue().canManage(
                admin.getUiState().getValue().find("p-1")));

        FakeRepository memberRepository = new FakeRepository();
        memberRepository.nextPage = repository.nextPage;
        PollViewModel member = createViewModel(memberRepository, true, viewer(false));
        member.load();
        assertFalse(member.getUiState().getValue().canManage(
                member.getUiState().getValue().find("p-1")));

        member.deletePoll("p-1");
        assertEquals(PollActionError.NOT_ADMIN,
                member.getEvents().getValue().consume());
        assertTrue(memberRepository.deletes.isEmpty());

        admin.closePoll("p-1");
        assertEquals(1, repository.closes.size());
        repository.closes.get(0).succeed(poll("p-1", "other", PollStatus.CLOSED, null,
                List.of(option("o-1", "A", 0, List.of()),
                        option("o-2", "B", 0, List.of())),
                List.of(), 2L));
        assertEquals(PollStatus.CLOSED, admin.getUiState().getValue()
                .find("p-1").getStatus());
    }

    @Test
    public void createValidatesBoundsAndAddsTheCreatedPoll() {
        FakeRepository repository = new FakeRepository();
        PollViewModel viewModel = createViewModel(repository, true, viewer(false));
        viewModel.load();

        viewModel.createPoll(new PollDraft("", List.of("A", "B"), false, false, null));
        assertEquals(PollRepository.Failure.VALIDATION,
                viewModel.getUiState().getValue().getCreateError());
        assertTrue(repository.creates.isEmpty());

        viewModel.createPoll(new PollDraft(
                " Đi đâu? ", List.of(" Biển ", "Núi"), false, false, null));
        assertEquals(1, repository.creates.size());
        assertEquals("Đi đâu?", repository.creates.get(0).draft.getQuestion());
        repository.creates.get(0).succeed(poll("p-1", ME, PollStatus.OPEN, null,
                List.of(option("o-1", "Biển", 0, List.of()),
                        option("o-2", "Núi", 0, List.of())),
                List.of(), 3L));

        PollUiState state = viewModel.getUiState().getValue();
        assertEquals("p-1", state.getCreatedPollId());
        assertEquals(1, state.getPolls().size());
        viewModel.consumeCreatedPoll();
        assertNull(viewModel.getUiState().getValue().getCreatedPollId());
    }

    @Test
    public void aDirectConversationShowsNotAGroupWithoutRequestingPolls() {
        FakeRepository repository = new FakeRepository();
        PollViewModel viewModel = createViewModel(repository, false, viewer(false));

        viewModel.load();

        assertEquals(PollRepository.Failure.NOT_A_GROUP,
                viewModel.getUiState().getValue().getLoadError());
        assertEquals(0, repository.loads);
        assertTrue(viewModel.getUiState().getValue().getPolls().isEmpty());
    }

    @Test
    public void loadMoreUsesTheOpaqueCursor() {
        FakeRepository repository = new FakeRepository();
        repository.autoSucceed = false;
        PollViewModel viewModel = createViewModel(repository, true, viewer(false));
        viewModel.load();
        repository.pages.get(0).succeed(new PollPage(List.of(
                poll("p-1", "other", PollStatus.OPEN, null,
                        List.of(option("o-1", "A", 0, List.of()),
                                option("o-2", "B", 0, List.of())),
                        List.of(), 1L)), "cursor-1"));
        assertTrue(viewModel.getUiState().getValue().hasMore());

        viewModel.loadMore();
        assertEquals(2, repository.loadRequests.size());
        assertEquals("cursor-1", repository.loadRequests.get(1));
        repository.pages.get(1).succeed(new PollPage(List.of(
                poll("p-2", "other", PollStatus.OPEN, null,
                        List.of(option("o-1", "A", 0, List.of()),
                                option("o-2", "B", 0, List.of())),
                        List.of(), 1L)), null));

        assertEquals(2, viewModel.getUiState().getValue().getPolls().size());
        assertFalse(viewModel.getUiState().getValue().hasMore());
    }

    private PollViewModel createViewModel(
            FakeRepository repository, boolean group, FakeViewer viewer) {
        SavedStateHandle savedState = new SavedStateHandle(Map.of(
                PollViewModel.ARG_CONVERSATION_ID, CONVERSATION_ID,
                PollViewModel.ARG_IS_GROUP, group));
        return new PollViewModel(savedState, repository, viewer, CLOCK);
    }

    private FakeViewer viewer(boolean admin) {
        return new FakeViewer(List.of(
                new PollViewerSource.Member(ME, "Tôi", admin),
                new PollViewerSource.Member("other", "Bình", false)));
    }

    private static Poll poll(
            String id, String createdBy, PollStatus status,
            Instant closesAt, List<PollOption> options, List<String> mine, long version) {
        return poll(id, createdBy, status, closesAt, false, options, mine, version);
    }

    private static Poll poll(
            String id, String createdBy, PollStatus status, Instant closesAt,
            boolean anonymous, List<PollOption> options, List<String> mine, long version) {
        return new Poll(
                id, CONVERSATION_ID, createdBy, "Đi đâu?", false, anonymous, status,
                closesAt, status == PollStatus.CLOSED ? NOW : null,
                status == PollStatus.CLOSED ? createdBy : null,
                Instant.parse("2026-11-09T09:00:00Z"), version,
                options.isEmpty() ? 0 : 1, options, mine);
    }

    private static PollOption option(
            String id, String label, int count, List<String> voters) {
        return new PollOption(id, 0, label, count, voters);
    }

    private static final class FakeViewer implements PollViewerSource {
        private final List<Member> members;

        FakeViewer(List<Member> members) {
            this.members = members;
        }

        @Override
        public Cancellable load(String conversationId, Callback callback) {
            callback.onLoaded(members, ME);
            return () -> { };
        }
    }

    private static final class FakeRepository implements PollRepository {
        private PollPage nextPage = new PollPage(List.of(), null);
        private final List<String> loadRequests = new ArrayList<>();
        private final List<PendingPage> pages = new ArrayList<>();
        private final List<PendingChanges> changes = new ArrayList<>();
        private final List<PendingItem> votes = new ArrayList<>();
        private final List<PendingItem> retracts = new ArrayList<>();
        private final List<PendingItem> closes = new ArrayList<>();
        private final List<PendingDelete> deletes = new ArrayList<>();
        private final List<PendingCreate> creates = new ArrayList<>();
        private int loads;
        private boolean autoSucceed = true;

        @Override
        public Cancellable load(String conversationId, String before, PageCallback callback) {
            loads++;
            loadRequests.add(before);
            PendingPage pending = new PendingPage(callback);
            pages.add(pending);
            if (autoSucceed) {
                callback.onSuccess(nextPage);
            }
            return pending;
        }

        @Override
        public Cancellable changes(
                String conversationId, long since, int limit, ChangesCallback callback) {
            PendingChanges pending = new PendingChanges(since, callback);
            changes.add(pending);
            return pending;
        }

        @Override
        public Cancellable create(
                String conversationId, PollDraft draft, ItemCallback callback) {
            PendingCreate pending = new PendingCreate(draft, callback);
            creates.add(pending);
            return pending;
        }

        @Override
        public Cancellable vote(
                String conversationId, String pollId, List<String> optionIds,
                ItemCallback callback) {
            PendingItem pending = new PendingItem(optionIds, callback);
            votes.add(pending);
            return pending;
        }

        @Override
        public Cancellable retractVote(
                String conversationId, String pollId, ItemCallback callback) {
            PendingItem pending = new PendingItem(List.of(), callback);
            retracts.add(pending);
            return pending;
        }

        @Override
        public Cancellable close(
                String conversationId, String pollId, ItemCallback callback) {
            PendingItem pending = new PendingItem(List.of(), callback);
            closes.add(pending);
            return pending;
        }

        @Override
        public Cancellable delete(
                String conversationId, String pollId, DeleteCallback callback) {
            PendingDelete pending = new PendingDelete(callback);
            deletes.add(pending);
            return pending;
        }
    }

    private static final class PendingPage implements PollRepository.Cancellable {
        private final PollRepository.PageCallback callback;

        PendingPage(PollRepository.PageCallback callback) {
            this.callback = callback;
        }

        void succeed(PollPage page) {
            callback.onSuccess(page);
        }

        @Override public void cancel() { }
    }

    private static final class PendingChanges implements PollRepository.Cancellable {
        private final long since;
        private final PollRepository.ChangesCallback callback;

        PendingChanges(long since, PollRepository.ChangesCallback callback) {
            this.since = since;
            this.callback = callback;
        }

        void succeed(PollChanges page) {
            callback.onSuccess(page);
        }

        void fail(PollRepository.Failure failure) {
            callback.onFailure(failure);
        }

        @Override public void cancel() { }
    }

    private static final class PendingItem implements PollRepository.Cancellable {
        private final List<String> optionIds;
        private final PollRepository.ItemCallback callback;

        PendingItem(List<String> optionIds, PollRepository.ItemCallback callback) {
            this.optionIds = optionIds;
            this.callback = callback;
        }

        void succeed(Poll poll) {
            callback.onSuccess(poll);
        }

        void fail(PollRepository.Failure failure) {
            callback.onFailure(failure);
        }

        @Override public void cancel() { }
    }

    private static final class PendingDelete implements PollRepository.Cancellable {
        private final PollRepository.DeleteCallback callback;

        PendingDelete(PollRepository.DeleteCallback callback) {
            this.callback = callback;
        }

        void succeed() {
            callback.onSuccess();
        }

        void fail(PollRepository.Failure failure) {
            callback.onFailure(failure);
        }

        @Override public void cancel() { }
    }

    private static final class PendingCreate implements PollRepository.Cancellable {
        private final PollDraft draft;
        private final PollRepository.ItemCallback callback;

        PendingCreate(PollDraft draft, PollRepository.ItemCallback callback) {
            this.draft = draft;
            this.callback = callback;
        }

        void succeed(Poll poll) {
            callback.onSuccess(poll);
        }

        @Override public void cancel() { }
    }
}
