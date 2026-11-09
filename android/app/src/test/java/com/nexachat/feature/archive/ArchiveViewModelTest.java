package com.nexachat.feature.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public final class ArchiveViewModelTest {

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    @Test
    public void keysetPagesAppendItemsAndPassTheOpaqueCursorUnchanged() {
        FakeRepository repository = new FakeRepository();
        ArchiveViewModel viewModel = createViewModel(repository, new SavedStateHandle());

        viewModel.loadCurrentPage();
        assertEquals(ArchiveType.MEDIA, repository.request(0).query.getType());
        assertNull(repository.request(0).query.getCursor());
        assertEquals(ArchiveQuery.PAGE_SIZE, repository.request(0).query.getLimit());
        repository.request(0).succeed(new ArchivePage(
                List.of(item("message-1", "sender-1")), "next%2Fopaque"));

        viewModel.loadNextPage();

        assertEquals("next%2Fopaque", repository.request(1).query.getCursor());
        repository.request(1).succeed(new ArchivePage(
                List.of(item("message-2", "sender-2")), null));
        ArchivePageState page = viewModel.getUiState().getValue().getPage(ArchiveType.MEDIA);
        assertEquals(List.of("message-1", "message-2"), messageIds(page.getItems()));
        assertFalse(page.hasMore());
        assertNull(page.getCursor());
    }

    @Test
    public void senderAndDateFiltersResetPagesAndStartAtTheFirstCursor() {
        FakeRepository repository = new FakeRepository();
        ArchiveViewModel viewModel = createViewModel(repository, new SavedStateHandle());
        viewModel.loadCurrentPage();
        repository.request(0).succeed(new ArchivePage(
                List.of(item("old", "sender-1")), "old-cursor"));

        viewModel.selectSender("sender-2");
        ArchiveQuery senderQuery = repository.request(1).query;
        assertEquals("sender-2", senderQuery.getSenderId());
        assertNull(senderQuery.getCursor());
        repository.request(1).succeed(new ArchivePage(Collections.emptyList(), null));

        String from = "2026-11-01T00:00:00Z";
        String to = "2026-11-02T23:59:59.999999999Z";
        viewModel.selectDateRange(from, to);
        ArchiveQuery dateQuery = repository.request(2).query;
        assertEquals("sender-2", dateQuery.getSenderId());
        assertEquals(from, dateQuery.getFrom());
        assertEquals(to, dateQuery.getTo());
        assertNull(dateQuery.getCursor());
        assertTrue(viewModel.getUiState().getValue().hasFilters());
    }

    @Test
    public void handlesEmptyOfflineRetryAndForbiddenStates() {
        FakeRepository repository = new FakeRepository();
        ArchiveViewModel viewModel = createViewModel(repository, new SavedStateHandle());
        viewModel.loadCurrentPage();
        repository.request(0).succeed(new ArchivePage(Collections.emptyList(), null));
        assertTrue(viewModel.getUiState().getValue().getPage(ArchiveType.MEDIA).isEmpty());

        viewModel.selectType(ArchiveType.FILE);
        repository.request(1).fail(ArchiveRepository.Failure.OFFLINE);
        assertEquals(ArchiveRepository.Failure.OFFLINE,
                viewModel.getUiState().getValue().getPage(ArchiveType.FILE).getError());

        viewModel.retry();
        repository.request(2).fail(ArchiveRepository.Failure.FORBIDDEN);
        assertEquals(ArchiveRepository.Failure.FORBIDDEN,
                viewModel.getUiState().getValue().getPage(ArchiveType.FILE).getError());
    }

    @Test
    public void retainedViewModelKeepsCursorAcrossViewRecreation() {
        FakeRepository repository = new FakeRepository();
        SavedStateHandle savedState = new SavedStateHandle(
                Map.of(ArchiveViewModel.ARG_CONVERSATION_ID, "conversation-1"));
        ArchiveViewModel viewModel = createViewModel(repository, savedState);
        viewModel.loadCurrentPage();
        repository.request(0).succeed(new ArchivePage(
                List.of(item("message-1", "sender-1")), "cursor-after-first-page"));

        viewModel.loadCurrentPage();

        assertEquals(1, repository.requests.size());
        assertEquals("cursor-after-first-page",
                viewModel.getUiState().getValue().getPage(ArchiveType.MEDIA).getCursor());
        assertEquals(List.of("message-1"), messageIds(
                viewModel.getUiState().getValue().getPage(ArchiveType.MEDIA).getItems()));
    }

    @Test
    public void restoresSelectedTabAndFiltersFromSavedState() {
        FakeRepository repository = new FakeRepository();
        SavedStateHandle savedState = new SavedStateHandle(Map.of(
                ArchiveViewModel.ARG_CONVERSATION_ID, "conversation-1",
                "archive.type", "LINK",
                "archive.sender", "sender-2",
                "archive.from", "2026-11-01T00:00:00Z",
                "archive.to", "2026-11-02T23:59:59.999999999Z"));

        ArchiveViewModel viewModel = createViewModel(repository, savedState);
        ArchiveUiState state = viewModel.getUiState().getValue();

        assertEquals(ArchiveType.LINK, state.getSelectedType());
        assertEquals("sender-2", state.getSenderId());
        assertEquals("2026-11-01T00:00:00Z", state.getFrom());
        assertEquals("2026-11-02T23:59:59.999999999Z", state.getTo());
    }

    @Test
    public void switchingTabsRetainsEachTabsIndependentCursor() {
        FakeRepository repository = new FakeRepository();
        ArchiveViewModel viewModel = createViewModel(repository, new SavedStateHandle());
        viewModel.loadCurrentPage();
        repository.request(0).succeed(new ArchivePage(
                List.of(item("media-1", "sender-1")), "media-cursor"));

        viewModel.selectType(ArchiveType.LINK);
        repository.request(1).succeed(new ArchivePage(
                List.of(item("link-1", "sender-2")), "link-cursor"));
        viewModel.selectType(ArchiveType.MEDIA);
        viewModel.loadNextPage();

        assertEquals("media-cursor", repository.request(2).query.getCursor());
        assertEquals("link-cursor",
                viewModel.getUiState().getValue().getPage(ArchiveType.LINK).getCursor());
    }

    @Test
    public void repeatedCursorBreaksLoopAndSurfacesError() {
        FakeRepository repository = new FakeRepository();
        ArchiveViewModel viewModel = createViewModel(repository, new SavedStateHandle());
        viewModel.loadCurrentPage();
        repository.request(0).succeed(new ArchivePage(
                List.of(item("media-1", "sender-1")), "same-cursor"));

        viewModel.loadNextPage();
        repository.request(1).succeed(new ArchivePage(
                List.of(item("media-2", "sender-1")), "same-cursor"));

        ArchivePageState page = viewModel.getUiState().getValue().getPage(ArchiveType.MEDIA);
        assertNull(page.getCursor());
        assertFalse(page.hasMore());
        assertEquals(ArchiveRepository.Failure.UNKNOWN, page.getError());
    }

    @Test
    public void pagingCursorAndItemsPersistAcrossProcessDeath() {
        FakeRepository repository = new FakeRepository();
        SavedStateHandle savedState = new SavedStateHandle(
                Map.of(ArchiveViewModel.ARG_CONVERSATION_ID, "conversation-1"));
        ArchiveViewModel initialViewModel = createViewModel(repository, savedState);
        initialViewModel.loadCurrentPage();
        repository.request(0).succeed(new ArchivePage(
                List.of(item("message-1", "sender-1")), "cursor-after-p1"));

        // Simulate process death: recreation of ViewModel with the same SavedStateHandle
        ArchiveViewModel restoredViewModel = createViewModel(repository, savedState);
        ArchivePageState restoredPage = restoredViewModel.getUiState().getValue().getPage(ArchiveType.MEDIA);

        assertTrue(restoredPage.isLoaded());
        assertFalse(restoredPage.isLoading());
        assertTrue(restoredPage.hasMore());
        assertEquals("cursor-after-p1", restoredPage.getCursor());
        assertEquals(List.of("message-1"), messageIds(restoredPage.getItems()));

        // Paging continues seamlessly from the restored cursor
        restoredViewModel.loadNextPage();
        assertEquals(2, repository.requests.size());
        assertEquals("cursor-after-p1", repository.request(1).query.getCursor());
    }

    @Test
    public void deduplicatesOverlappingPagesAndRetainsMultipleAttachmentsPerMessage() {
        FakeRepository repository = new FakeRepository();
        ArchiveViewModel viewModel = createViewModel(repository, new SavedStateHandle());
        viewModel.loadCurrentPage();

        ArchivePage.Item att1 = itemWithAttachment("msg-1", "att-1", "file1.png");
        ArchivePage.Item att2 = itemWithAttachment("msg-1", "att-2", "file2.png");
        repository.request(0).succeed(new ArchivePage(List.of(att1, att2), "cursor-p1"));

        viewModel.loadNextPage();
        ArchivePage.Item att3 = itemWithAttachment("msg-1", "att-3", "file3.png");
        // Page 2 overlaps on att2, and adds att3
        repository.request(1).succeed(new ArchivePage(List.of(att2, att3), null));

        ArchivePageState page = viewModel.getUiState().getValue().getPage(ArchiveType.MEDIA);
        assertEquals(3, page.getItems().size());
        assertEquals("att-1", page.getItems().get(0).getAttachment().getId());
        assertEquals("att-2", page.getItems().get(1).getAttachment().getId());
        assertEquals("att-3", page.getItems().get(2).getAttachment().getId());
    }

    @Test
    public void emptyPageWithNextCursorAutoAdvancesBoundedScanWithoutEndlessLoop() {
        FakeRepository repository = new FakeRepository();
        ArchiveViewModel viewModel = createViewModel(repository, new SavedStateHandle());
        viewModel.selectType(ArchiveType.LINK);

        // Page 1: server bounded scan returns empty list with nextCursor
        repository.request(0).succeed(new ArchivePage(Collections.emptyList(), "scan-cursor-1"));
        // Auto-advance round 1
        assertEquals(2, repository.requests.size());
        assertEquals("scan-cursor-1", repository.request(1).query.getCursor());
        repository.request(1).succeed(new ArchivePage(Collections.emptyList(), "scan-cursor-2"));

        // Auto-advance round 2
        assertEquals(3, repository.requests.size());
        assertEquals("scan-cursor-2", repository.request(2).query.getCursor());
        repository.request(2).succeed(new ArchivePage(Collections.emptyList(), "scan-cursor-3"));

        // Auto-advance round 3 (reaches MAX_EMPTY_SCAN_ROUNDS = 3)
        assertEquals(4, repository.requests.size());
        assertEquals("scan-cursor-3", repository.request(3).query.getCursor());
        repository.request(3).succeed(new ArchivePage(Collections.emptyList(), "scan-cursor-4"));

        // Must stop auto-advancing: no more background requests, loading = false, cursor preserved
        assertEquals(4, repository.requests.size());
        ArchivePageState page = viewModel.getUiState().getValue().getPage(ArchiveType.LINK);
        assertFalse(page.isLoading());
        assertTrue(page.hasMore());
        assertEquals("scan-cursor-4", page.getCursor());
        assertTrue(page.getItems().isEmpty());
    }

    @Test
    public void cyclicCursorBreaksLoopImmediately() {
        FakeRepository repository = new FakeRepository();
        ArchiveViewModel viewModel = createViewModel(repository, new SavedStateHandle());
        viewModel.loadCurrentPage();
        repository.request(0).succeed(new ArchivePage(List.of(item("m-1", "s-1")), "cursor-A"));

        viewModel.loadNextPage();
        repository.request(1).succeed(new ArchivePage(List.of(item("m-2", "s-1")), "cursor-B"));

        viewModel.loadNextPage();
        // Server returns cursor-A again, causing a cycle
        repository.request(2).succeed(new ArchivePage(List.of(item("m-3", "s-1")), "cursor-A"));

        ArchivePageState page = viewModel.getUiState().getValue().getPage(ArchiveType.MEDIA);
        assertNull(page.getCursor());
        assertFalse(page.hasMore());
        assertEquals(ArchiveRepository.Failure.UNKNOWN, page.getError());
    }

    @Test
    public void handlesNotFound404StateAsTerminalErrorWithoutRetrying() {
        FakeRepository repository = new FakeRepository();
        ArchiveViewModel viewModel = createViewModel(repository, new SavedStateHandle());
        viewModel.loadCurrentPage();
        repository.request(0).fail(ArchiveRepository.Failure.NOT_FOUND);

        ArchivePageState page = viewModel.getUiState().getValue().getPage(ArchiveType.MEDIA);
        assertEquals(ArchiveRepository.Failure.NOT_FOUND, page.getError());

        // 404 is a terminal permission/not-found error, retry should not request again
        viewModel.retry();
        assertEquals(1, repository.requests.size());
    }

    @Test
    public void clearFiltersResetsFilterStateAndReloads() {
        FakeRepository repository = new FakeRepository();
        ArchiveViewModel viewModel = createViewModel(repository, new SavedStateHandle());
        viewModel.selectSender("sender-1");
        repository.request(0).succeed(new ArchivePage(List.of(item("m-1", "sender-1")), null));
        assertTrue(viewModel.getUiState().getValue().hasFilters());

        viewModel.clearFilters();
        assertFalse(viewModel.getUiState().getValue().hasFilters());
        assertNull(viewModel.getUiState().getValue().getSenderId());
        assertEquals(2, repository.requests.size());
        assertNull(repository.request(1).query.getSenderId());
    }

    @Test
    public void rejectsInvalidDateRangeAndUnknownSender() {
        FakeRepository repository = new FakeRepository();
        ArchiveViewModel viewModel = createViewModel(repository, new SavedStateHandle());

        // Invalid date range where from > to
        viewModel.selectDateRange("2026-11-10T00:00:00Z", "2026-11-01T00:00:00Z");
        assertNull(viewModel.getUiState().getValue().getFrom());
        assertNull(viewModel.getUiState().getValue().getTo());

        // Unknown sender not in members list
        viewModel.selectSender("non-existent-user");
        assertNull(viewModel.getUiState().getValue().getSenderId());
    }

    private static ArchiveViewModel createViewModel(
            FakeRepository repository,
            SavedStateHandle state) {
        if (state.get(ArchiveViewModel.ARG_CONVERSATION_ID) == null) {
            state.set(ArchiveViewModel.ARG_CONVERSATION_ID, "conversation-1");
        }
        ArchiveSenderSource senders = (conversationId, callback) -> {
            callback.onLoaded(List.of(
                    new ArchiveSender("sender-1", "An"),
                    new ArchiveSender("sender-2", "Bình")));
            return () -> { };
        };
        return new ArchiveViewModel(state, repository, senders);
    }

    private static ArchivePage.Item item(String messageId, String senderId) {
        return new ArchivePage.Item(
                messageId, 1L, senderId, "2026-11-09T10:00:00Z", "IMAGE", null, null);
    }

    private static ArchivePage.Item itemWithAttachment(String messageId, String attachmentId, String fileName) {
        return new ArchivePage.Item(
                messageId, 1L, "sender-1", "2026-11-09T10:00:00Z", "IMAGE",
                new ArchivePage.Attachment(attachmentId, fileName, "image/png", 1024L, null, null, 100, 100),
                null);
    }

    private static List<String> messageIds(List<ArchivePage.Item> items) {
        List<String> ids = new ArrayList<>();
        for (ArchivePage.Item item : items) ids.add(item.getMessageId());
        return ids;
    }

    private static final class FakeRepository implements ArchiveRepository {
        private final List<PendingRequest> requests = new ArrayList<>();

        @Override
        public Cancellable load(ArchiveQuery query, Callback callback) {
            PendingRequest request = new PendingRequest(query, callback);
            requests.add(request);
            return () -> request.cancelled = true;
        }

        private PendingRequest request(int index) {
            return requests.get(index);
        }
    }

    private static final class PendingRequest {
        private final ArchiveQuery query;
        private final ArchiveRepository.Callback callback;
        private boolean cancelled;

        private PendingRequest(ArchiveQuery query, ArchiveRepository.Callback callback) {
            this.query = query;
            this.callback = callback;
        }

        private void succeed(ArchivePage page) {
            if (!cancelled) callback.onSuccess(page);
        }

        private void fail(ArchiveRepository.Failure failure) {
            if (!cancelled) callback.onFailure(failure);
        }
    }
}
