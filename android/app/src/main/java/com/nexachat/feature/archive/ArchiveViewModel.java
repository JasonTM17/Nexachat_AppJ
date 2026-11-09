package com.nexachat.feature.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;

/** Owns archive filters and keyset cursors so they survive a configuration change. */
@HiltViewModel
public final class ArchiveViewModel extends ViewModel {

    public static final String ARG_CONVERSATION_ID = "conversationId";
    private static final String KEY_TYPE = "archive.type";
    private static final String KEY_SENDER = "archive.sender";
    private static final String KEY_FROM = "archive.from";
    private static final String KEY_TO = "archive.to";
    private static final String KEY_CURSOR_PREFIX = "archive.cursor.";
    private static final String KEY_ITEMS_PREFIX = "archive.items.";
    private static final String KEY_HAS_MORE_PREFIX = "archive.has_more.";
    private static final String KEY_LOADED_PREFIX = "archive.loaded.";
    private static final int MAX_EMPTY_SCAN_ROUNDS = 3;

    private final SavedStateHandle savedState;
    private final String conversationId;
    private final ArchiveRepository repository;
    private final ArchiveSenderSource senderSource;
    private final EnumMap<ArchiveType, ArchivePageState> pages =
            new EnumMap<>(ArchiveType.class);
    private final EnumMap<ArchiveType, ArchiveRepository.Cancellable> requests =
            new EnumMap<>(ArchiveType.class);
    private final EnumMap<ArchiveType, Set<String>> seenCursors =
            new EnumMap<>(ArchiveType.class);
    private final EnumMap<ArchiveType, Integer> emptyScanRounds =
            new EnumMap<>(ArchiveType.class);
    private final MutableLiveData<ArchiveUiState> uiState =
            new MutableLiveData<>(new ArchiveUiState());

    private ArchiveType selectedType;
    private List<ArchiveSender> senders = Collections.emptyList();
    private boolean sendersLoaded;
    @Nullable private String senderId;
    @Nullable private String from;
    @Nullable private String to;
    @Nullable private ArchiveSenderSource.Cancellable senderRequest;
    private int generation;

    @Inject
    public ArchiveViewModel(
            @NonNull SavedStateHandle savedState,
            @NonNull ArchiveRepository repository,
            @NonNull ArchiveSenderSource senderSource) {
        this.savedState = Objects.requireNonNull(savedState, "savedState");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.senderSource = Objects.requireNonNull(senderSource, "senderSource");
        String savedConversationId = savedState.get(ARG_CONVERSATION_ID);
        conversationId = savedConversationId == null ? "" : savedConversationId;
        selectedType = parseType(savedState.get(KEY_TYPE));
        senderId = savedState.get(KEY_SENDER);
        from = savedState.get(KEY_FROM);
        to = savedState.get(KEY_TO);
        for (ArchiveType type : ArchiveType.values()) {
            List<ArchivePage.Item> savedItems = savedState.get(KEY_ITEMS_PREFIX + type.name());
            String savedCursor = savedState.get(KEY_CURSOR_PREFIX + type.name());
            Boolean savedHasMore = savedState.get(KEY_HAS_MORE_PREFIX + type.name());
            Boolean savedLoaded = savedState.get(KEY_LOADED_PREFIX + type.name());
            if (savedItems != null && !savedItems.isEmpty()) {
                pages.put(type, new ArchivePageState(
                        savedItems,
                        savedCursor,
                        savedHasMore != null ? savedHasMore : (savedCursor != null),
                        false,
                        savedLoaded != null ? savedLoaded : true,
                        null));
            } else if (savedCursor != null) {
                pages.put(type, new ArchivePageState(
                        Collections.emptyList(), savedCursor, true, false, false, null));
            } else {
                pages.put(type, new ArchivePageState());
            }
        }
        publish();
        loadSenders();
    }

    public LiveData<ArchiveUiState> getUiState() {
        return uiState;
    }

    public void loadCurrentPage() {
        ArchivePageState current = pages.get(selectedType);
        if (!current.isLoaded() && !current.isLoading()) {
            requestPage(selectedType, null);
        }
    }

    public void selectType(@NonNull ArchiveType type) {
        if (selectedType == type) {
            return;
        }
        selectedType = type;
        savedState.set(KEY_TYPE, type.name());
        publish();
        loadCurrentPage();
    }

    public void selectSender(@Nullable String nextSenderId) {
        if (Objects.equals(senderId, nextSenderId)) {
            return;
        }
        if (nextSenderId != null && senders.stream()
                .noneMatch(sender -> nextSenderId.equals(sender.getId()))) {
            return;
        }
        senderId = nextSenderId;
        savedState.set(KEY_SENDER, nextSenderId);
        resetPagesAndLoad();
    }

    public void selectDateRange(@Nullable String nextFrom, @Nullable String nextTo) {
        if ((nextFrom == null) != (nextTo == null)
                || (nextFrom != null && nextFrom.compareTo(nextTo) > 0)) {
            return;
        }
        if (Objects.equals(from, nextFrom) && Objects.equals(to, nextTo)) {
            return;
        }
        from = nextFrom;
        to = nextTo;
        savedState.set(KEY_FROM, nextFrom);
        savedState.set(KEY_TO, nextTo);
        resetPagesAndLoad();
    }

    public void clearFilters() {
        if (!hasFilters()) {
            return;
        }
        senderId = null;
        from = null;
        to = null;
        savedState.set(KEY_SENDER, null);
        savedState.set(KEY_FROM, null);
        savedState.set(KEY_TO, null);
        resetPagesAndLoad();
    }

    public void loadNextPage() {
        ArchivePageState current = pages.get(selectedType);
        if (current.isLoading() || !current.isLoaded() || !current.hasMore()
                || current.getCursor() == null) {
            return;
        }
        requestPage(selectedType, current.getCursor());
    }

    public void retry() {
        ArchivePageState current = pages.get(selectedType);
        if (current.isLoading() || current.getError() == null
                || current.getError() == ArchiveRepository.Failure.FORBIDDEN
                || current.getError() == ArchiveRepository.Failure.NOT_FOUND) {
            return;
        }
        requestPage(selectedType, current.getCursor());
    }

    private void loadSenders() {
        senderRequest = senderSource.load(conversationId, values -> {
            senders = List.copyOf(values);
            sendersLoaded = true;
            if (senderId != null && senders.stream()
                    .noneMatch(sender -> senderId.equals(sender.getId()))) {
                senderId = null;
                savedState.set(KEY_SENDER, null);
            }
            publish();
        });
    }

    private void resetPagesAndLoad() {
        generation++;
        for (ArchiveRepository.Cancellable request : requests.values()) {
            request.cancel();
        }
        requests.clear();
        seenCursors.clear();
        emptyScanRounds.clear();
        for (ArchiveType type : ArchiveType.values()) {
            pages.put(type, new ArchivePageState());
            savedState.set(KEY_ITEMS_PREFIX + type.name(), null);
            savedState.set(KEY_CURSOR_PREFIX + type.name(), null);
            savedState.set(KEY_HAS_MORE_PREFIX + type.name(), null);
            savedState.set(KEY_LOADED_PREFIX + type.name(), null);
        }
        publish();
        requestPage(selectedType, null);
    }

    private void requestPage(ArchiveType type, @Nullable String cursor) {
        ArchivePageState current = pages.get(type);
        pages.put(type, new ArchivePageState(
                current.getItems(), current.getCursor(), current.hasMore(), true,
                current.isLoaded(), null));
        publish();

        int requestGeneration = generation;
        ArchiveQuery query = new ArchiveQuery(
                conversationId, type, senderId, from, to, ArchiveQuery.PAGE_SIZE, cursor);
        ArchiveRepository.Cancellable request = repository.load(query,
                new ArchiveRepository.Callback() {
                    @Override
                    public void onSuccess(ArchivePage response) {
                        if (requestGeneration != generation) {
                            return;
                        }
                        applyPage(type, cursor, response);
                    }

                    @Override
                    public void onFailure(ArchiveRepository.Failure failure) {
                        if (requestGeneration != generation) {
                            return;
                        }
                        ArchivePageState old = pages.get(type);
                        pages.put(type, new ArchivePageState(
                                old.getItems(), old.getCursor(), old.hasMore(), false,
                                old.isLoaded(), failure));
                        requests.remove(type);
                        publish();
                    }
                });
        requests.put(type, request);
    }

    private void applyPage(
            ArchiveType type,
            @Nullable String requestedCursor,
            @NonNull ArchivePage response) {
        ArchivePageState old = pages.get(type);
        Map<String, ArchivePage.Item> merged = new LinkedHashMap<>();
        for (ArchivePage.Item item : old.getItems()) {
            merged.put(item.stableKey(), item);
        }
        for (ArchivePage.Item item : response.getItems()) {
            merged.putIfAbsent(item.stableKey(), item);
        }

        String nextCursor = normalizedCursor(response.getNextCursor());
        Set<String> typeSeen = seenCursors.computeIfAbsent(type, k -> new HashSet<>());
        if (requestedCursor != null) {
            typeSeen.add(requestedCursor);
        }
        boolean isLoop = nextCursor != null && (nextCursor.equals(requestedCursor) || typeSeen.contains(nextCursor));
        if (isLoop) {
            pages.put(type, new ArchivePageState(
                    new ArrayList<>(merged.values()), null, false,
                    false, true, ArchiveRepository.Failure.UNKNOWN));
            requests.remove(type);
            emptyScanRounds.put(type, 0);
            savedState.set(KEY_ITEMS_PREFIX + type.name(), new ArrayList<>(merged.values()));
            savedState.set(KEY_CURSOR_PREFIX + type.name(), null);
            savedState.set(KEY_HAS_MORE_PREFIX + type.name(), false);
            savedState.set(KEY_LOADED_PREFIX + type.name(), true);
            publish();
            return;
        }

        boolean emptyPageWithNextCursor = response.getItems().isEmpty() && nextCursor != null;
        int currentEmptyRounds = emptyScanRounds.getOrDefault(type, 0);
        if (emptyPageWithNextCursor && currentEmptyRounds < MAX_EMPTY_SCAN_ROUNDS) {
            emptyScanRounds.put(type, currentEmptyRounds + 1);
            pages.put(type, new ArchivePageState(
                    new ArrayList<>(merged.values()), nextCursor, true,
                    true, old.isLoaded(), null));
            requests.remove(type);
            publish();
            requestPage(type, nextCursor);
            return;
        }

        if (!response.getItems().isEmpty()) {
            emptyScanRounds.put(type, 0);
        }
        pages.put(type, new ArchivePageState(
                new ArrayList<>(merged.values()), nextCursor, nextCursor != null,
                false, true, null));
        requests.remove(type);
        savedState.set(KEY_ITEMS_PREFIX + type.name(), new ArrayList<>(merged.values()));
        savedState.set(KEY_CURSOR_PREFIX + type.name(), nextCursor);
        savedState.set(KEY_HAS_MORE_PREFIX + type.name(), nextCursor != null);
        savedState.set(KEY_LOADED_PREFIX + type.name(), true);
        publish();
    }

    @Nullable
    private static String normalizedCursor(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean hasFilters() {
        return senderId != null || from != null || to != null;
    }

    @NonNull
    private static ArchiveType parseType(@Nullable String value) {
        if (value != null) {
            try {
                return ArchiveType.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                // A stale tab value should reopen the default tab.
            }
        }
        return ArchiveType.MEDIA;
    }

    private void publish() {
        uiState.setValue(new ArchiveUiState(
                selectedType, senders, sendersLoaded, senderId, from, to, pages));
    }

    @Override
    protected void onCleared() {
        for (ArchiveRepository.Cancellable request : requests.values()) {
            request.cancel();
        }
        requests.clear();
        if (senderRequest != null) {
            senderRequest.cancel();
        }
        super.onCleared();
    }
}
