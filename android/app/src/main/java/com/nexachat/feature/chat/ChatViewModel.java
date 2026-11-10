package com.nexachat.feature.chat;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.ui.ForwardFilter;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.AttachmentRepository;
import com.nexachat.data.AttachmentUploadEvent;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.data.LocalAttachment;
import com.nexachat.data.MessageJump;
import com.nexachat.data.MessageRepository;
import com.nexachat.data.GroupDetail;
import com.nexachat.data.GroupMember;
import com.nexachat.data.MessageMentionCodec;
import com.nexachat.data.MessageSearchResult;
import com.nexachat.data.PeerPresence;
import com.nexachat.data.SettingsRepository;
import com.nexachat.data.TypingEvent;
import com.nexachat.feature.media.PendingAttachment;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import javax.inject.Inject;

/**
 * Backs {@code chat/{conversationId}}. Repository streams are observed on the
 * main scheduler before they update immutable render state; repository work is
 * subscribed on {@link Schedulers#io()} and all subscriptions are cleared
 * with the ViewModel.
 */
@HiltViewModel
public final class ChatViewModel extends ViewModel {

    public static final long TYPING_SEND_INTERVAL_MS = 3_000L;
    public static final long PEER_TYPING_TTL_MS = 5_000L;
    public static final int MIN_SEARCH_CHARS = 2;
    public static final long SEARCH_DEBOUNCE_MS = 300L;
    public static final int SEARCH_PAGE_SIZE = MessageApi.DEFAULT_SEARCH_LIMIT;
    public static final String ERROR_MESSAGE_GONE = "MESSAGE_NOT_FOUND";
    public static final String ERROR_RECALL_WINDOW_EXPIRED = "RECALL_WINDOW_EXPIRED";
    private static final String KEY_INPUT = "chat_input";
    private static final String KEY_MENTIONS = "chat_mentions";
    private static final String KEY_MENTION_ALL_OFFSET = "chat_mention_all_offset";
    private static final String KEY_MENTION_ALL_LENGTH = "chat_mention_all_length";

    private final String conversationId;
    private final SavedStateHandle savedStateHandle;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final AttachmentRepository attachmentRepository;
    private final RealtimeSession session;
    private final MutableLiveData<RecallWindowConfig> recallWindowConfig =
            new MutableLiveData<>(RecallWindowConfig.DEFAULT);
    @Nullable private final SettingsRepository settingsRepository;
    private final MutableLiveData<UiState> mutableUiState;
    private final MutableLiveData<AiOptOutState> aiOptOutState =
            new MutableLiveData<>(new AiOptOutState());
    private final MutableLiveData<ChatSearchState> mutableSearchState =
            new MutableLiveData<>(new ChatSearchState());
    private final MutableLiveData<List<GroupMember>> mutableGroupMembers =
            new MutableLiveData<>(List.of());
    private final ChatSingleLiveEvent<ChatActionEvent> actionEvents =
            new ChatSingleLiveEvent<>();
    private final ChatSingleLiveEvent<String> jumpTargets = new ChatSingleLiveEvent<>();
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final SerialDisposable searchDisposable = new SerialDisposable();
    private final Map<String, CompositeDisposable> uploadDisposables = new LinkedHashMap<>();
    private final AtomicLong searchGeneration = new AtomicLong();

    @Nullable private Disposable typingTtlDisposable;
    private long searchCursor = -1L;
    private long lastTypingSentMs;
    private long lastReadSeq;
    @Nullable private Long unreadDividerReadSnapshot;
    private boolean loadingOlder;
    private boolean webSocketOpen;
    private boolean restReachable;

    @Inject
    public ChatViewModel(
            SavedStateHandle savedStateHandle,
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            AttachmentRepository attachmentRepository,
            RealtimeSession session,
            TokenStore tokenStore,
            @Nullable SettingsRepository settingsRepository) {
        this.savedStateHandle = Objects.requireNonNull(savedStateHandle, "savedStateHandle");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.conversationRepository = Objects.requireNonNull(
                conversationRepository, "conversationRepository");
        this.attachmentRepository = Objects.requireNonNull(
                attachmentRepository, "attachmentRepository");
        this.session = Objects.requireNonNull(session, "session");
        if (tokenStore != null) {
            disposables.add(tokenStore.observeRecallWindowConfig()
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(recallWindowConfig::setValue, ignored -> {}));
        }
        this.settingsRepository = settingsRepository;
        String savedConversationId = savedStateHandle.get("conversationId");
        this.conversationId = savedConversationId == null ? "" : savedConversationId;
        String input = savedStateHandle.get(KEY_INPUT);
        this.mutableUiState = new MutableLiveData<>(new UiState(
                input == null ? "" : input));
        disposables.add(searchDisposable);
        observeRepositoryState();
        initializeConversation();
    }

    public ChatViewModel(
            SavedStateHandle savedStateHandle,
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            AttachmentRepository attachmentRepository,
            RealtimeSession session) {
        this(savedStateHandle, messageRepository, conversationRepository,
                attachmentRepository, session, null, null);
    }

    public ChatViewModel(
            SavedStateHandle savedStateHandle,
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            AttachmentRepository attachmentRepository,
            RealtimeSession session,
            TokenStore tokenStore) {
        this(savedStateHandle, messageRepository, conversationRepository,
                attachmentRepository, session, tokenStore, null);
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    public LiveData<RecallWindowConfig> getRecallWindowConfig() {
        return recallWindowConfig;
    }

    /** Live per-conversation AI opt-out state used by the settings dialog. */
    public LiveData<AiOptOutState> getAiOptOutState() {
        return aiOptOutState;
    }

    public LiveData<ChatSearchState> getSearchState() {
        return mutableSearchState;
    }

    public LiveData<List<GroupMember>> getGroupMembers() { return mutableGroupMembers; }

    public MentionDraft getMentionDraft() {
        UiState state = currentUiState();
        String content = state.getInput();
        String json = savedStateHandle.get(KEY_MENTIONS);
        int allOffset = savedStateHandle.get(KEY_MENTION_ALL_OFFSET) == null
                ? -1 : savedStateHandle.get(KEY_MENTION_ALL_OFFSET);
        int allLength = savedStateHandle.get(KEY_MENTION_ALL_LENGTH) == null
                ? 0 : savedStateHandle.get(KEY_MENTION_ALL_LENGTH);
        return MentionDraft.restore(content, MessageMentionCodec.decode(json), allOffset, allLength);
    }

    public void loadGroupMembers() {
        disposables.add(conversationRepository.groupDetail(conversationId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?> success
                            && success.getValue() instanceof GroupDetail detail) {
                        mutableGroupMembers.setValue(detail.getMembers());
                    }
                }, this::surfaceStreamFailure));
    }

    public ChatSingleLiveEvent<ChatActionEvent> getActionEvents() {
        return actionEvents;
    }

    public ChatSingleLiveEvent<String> getJumpTargets() {
        return jumpTargets;
    }

    private void observeRepositoryState() {
        disposables.add(messageRepository.messagesFlow(conversationId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::applyMessages, this::surfaceStreamFailure));
        disposables.add(conversationRepository.conversationFlow(conversationId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(summary -> summary.ifPresent(this::applyConversation),
                        this::surfaceStreamFailure));
        disposables.add(session.getIsOpen()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(connected -> {
                    webSocketOpen = Boolean.TRUE.equals(connected);
                    updateConnectivityState();
                },
                        this::surfaceStreamFailure));
        disposables.add(messageRepository.getRestReachability()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(reachable -> {
                    restReachable = Boolean.TRUE.equals(reachable);
                    updateConnectivityState();
                },
                        this::surfaceStreamFailure));
        if (settingsRepository != null) {
            disposables.add(settingsRepository.getShowSeen()
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(show -> updateUiState(state -> state.withShowSeen(Boolean.TRUE.equals(show))),
                            this::surfaceStreamFailure));
        }
        disposables.add(messageRepository.pinsFlow(conversationId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(pins -> updateUiState(state -> state.withPins(pins)),
                        this::surfaceStreamFailure));
        disposables.add(messageRepository.getInteractionEvents()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(error -> updateUiState(state -> state.withError(error)),
                        this::surfaceStreamFailure));
        disposables.add(messageRepository.getTypingEvents()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onTypingEvent, this::surfaceStreamFailure));
        disposables.add(messageRepository.messagesFlow(conversationId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::markNewestIncomingRead, this::surfaceStreamFailure));
    }

    private void updateConnectivityState() {
        boolean connected = webSocketOpen || restReachable;
        updateUiState(state -> state.withConnected(connected));
    }

    private void initializeConversation() {
        if (!conversationId.isBlank()) {
            long inbox = conversationRepository.peekMyLastReadSeq(conversationId);
            long memory = messageRepository.peekLocalReadSeq(conversationId);
            long snapshot = UnreadDividerRule.resolveReadWatermark(inbox, memory, null, false);
            if (snapshot > 0L) {
                unreadDividerReadSnapshot = snapshot;
            }
        }
        disposables.add(messageRepository.openConversation(conversationId)
                .subscribeOn(Schedulers.io())
                .andThen(messageRepository.refreshHistory(conversationId, null)
                        .subscribeOn(Schedulers.io()))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?> success
                            && success.getValue() instanceof com.nexachat.core.network.dto.MessagePageDto page) {
                        updateUiState(state -> state.withHasMore(page.getHasMore()));
                    } else if (result instanceof Result.Failure<?> failure) {
                        updateUiState(state -> state.withError(failure.getError()));
                    }
                    updateUiState(state -> state.withLoading(false));
                }, error -> {
                    updateUiState(state -> state.withError(toApiError(error)));
                    updateUiState(state -> state.withLoading(false));
                }));
    }

    private void applyMessages(List<ChatMessage> messages) {
        updateUiState(state -> state.withMessages(messages));
    }

    private void applyConversation(ConversationSummary conversation) {
        if (conversation == null) {
            return;
        }
        AiOptOutState current = aiOptOutState.getValue();
        if (current != null && !current.isBusy()) {
            aiOptOutState.setValue(current.withOptOut(conversation.isAiOptOut()));
        }
        updateUiState(state -> state.withConversation(conversation));
    }

    /** Reads the caller's own AI opt-out state; called when the settings dialog opens. */
    public void loadAiOptOut() {
        if (conversationId.isBlank()) return;
        AiOptOutState current = currentAiOptOutState();
        aiOptOutState.setValue(current.withLoading(true).withError(null));
        disposables.add(conversationRepository.getAiOptOut(conversationId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> {
                            AiOptOutState state = currentAiOptOutState().withLoading(false);
                            if (result instanceof Result.Success<?> success) {
                                aiOptOutState.setValue(state.withOptOut(
                                        Boolean.TRUE.equals(success.getValue())).withError(null));
                            } else if (result instanceof Result.Failure<?> failure) {
                                aiOptOutState.setValue(state.withError(failure.getError()));
                            }
                        },
                        error -> aiOptOutState.setValue(currentAiOptOutState()
                                .withLoading(false)
                                .withError(toApiError(error)))));
    }

    /**
     * Writes the AI opt-out through the server first. The switch moves
     * optimistically, then reverts with a visible error if the call fails.
     */
    public void setAiOptOut(boolean optOut) {
        if (conversationId.isBlank()) return;
        AiOptOutState before = currentAiOptOutState();
        if (before.isSaving() || before.getOptOut() == optOut) return;
        aiOptOutState.setValue(before.withSaving(true).withOptOut(optOut).withError(null));
        disposables.add(conversationRepository.setAiOptOut(conversationId, optOut)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> {
                            AiOptOutState state = currentAiOptOutState().withSaving(false);
                            if (result instanceof Result.Success<?> success) {
                                aiOptOutState.setValue(state.withOptOut(
                                        Boolean.TRUE.equals(success.getValue())).withError(null));
                            } else if (result instanceof Result.Failure<?> failure) {
                                aiOptOutState.setValue(state
                                        .withOptOut(before.getOptOut())
                                        .withError(failure.getError()));
                            }
                        },
                        error -> aiOptOutState.setValue(currentAiOptOutState()
                                .withSaving(false)
                                .withOptOut(before.getOptOut())
                                .withError(toApiError(error)))));
    }

    /** Mutes this conversation until {@code until}, or forever when null. */
    public void muteConversation(@Nullable java.time.Instant until) {
        runPreference(
                until == null
                        ? conversationRepository.muteConversationForever(conversationId)
                        : conversationRepository.muteConversation(conversationId, until),
                ChatActionEvent.PreferenceChanged.Kind.MUTED);
    }

    /** One-tap unmute. */
    public void unmuteConversation() {
        runPreference(conversationRepository.unmuteConversation(conversationId),
                ChatActionEvent.PreferenceChanged.Kind.UNMUTED);
    }

    /** Archives or restores this conversation for the caller. */
    public void setConversationArchived(boolean archived) {
        runPreference(conversationRepository.setConversationArchived(conversationId, archived),
                archived
                        ? ChatActionEvent.PreferenceChanged.Kind.ARCHIVED
                        : ChatActionEvent.PreferenceChanged.Kind.UNARCHIVED);
    }

    private void runPreference(
            Single<Result<Void>> request, ChatActionEvent.PreferenceChanged.Kind successKind) {
        if (conversationId.isBlank()) return;
        disposables.add(request
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> {
                            if (result instanceof Result.Success<?>) {
                                actionEvents.emit(new ChatActionEvent.PreferenceChanged(successKind));
                            } else if (result instanceof Result.Failure<?> failure) {
                                updateUiState(state -> state.withError(failure.getError()));
                            }
                        },
                        error -> updateUiState(state -> state.withError(toApiError(error)))));
    }

    private AiOptOutState currentAiOptOutState() {
        AiOptOutState state = aiOptOutState.getValue();
        return state == null ? new AiOptOutState() : state;
    }

    /** Read watermark retained for the unread divider before this screen marks messages read. */
    public long peekLocalReadSeq() {
        return conversationId.isBlank() ? 0L : messageRepository.peekLocalReadSeq(conversationId);
    }

    @Nullable
    public Long getUnreadDividerReadSnapshot() {
        return unreadDividerReadSnapshot;
    }

    public long peekMyLastReadSeq() {
        return conversationId.isBlank()
                ? 0L
                : conversationRepository.peekMyLastReadSeq(conversationId);
    }

    private void markNewestIncomingRead(List<ChatMessage> messages) {
        long newestIncoming = -1L;
        for (ChatMessage message : messages) {
            Long sequence = message.getSeq();
            if (!message.getMine() && sequence != null && sequence > newestIncoming) {
                newestIncoming = sequence;
            }
        }
        if (newestIncoming <= lastReadSeq) {
            return;
        }
        lastReadSeq = newestIncoming;
        disposables.add(messageRepository.sendRead(conversationId, newestIncoming)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> { }, this::surfaceStreamFailure));
    }

    private void onTypingEvent(TypingEvent event) {
        if (!conversationId.equals(event.getConversationId())) {
            return;
        }
        disposables.add(messageRepository.isMine(event.getUserId())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(isMine -> {
                    if (isMine) {
                        return;
                    }
                    updateUiState(state -> state.withPeerTyping(true));
                    if (typingTtlDisposable != null) {
                        typingTtlDisposable.dispose();
                    }
                    typingTtlDisposable = Flowable.timer(
                                    PEER_TYPING_TTL_MS, TimeUnit.MILLISECONDS, Schedulers.computation())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(ignored -> updateUiState(state -> state.withPeerTyping(false)),
                                    this::surfaceStreamFailure);
                    disposables.add(typingTtlDisposable);
                }, this::surfaceStreamFailure));
    }

    // ---- user actions ----

    public void onInputChange(String value) {
        updateUiState(state -> state.withInput(value));
        // The SavedStateHandle is owned by the constructor but intentionally
        // does not leak into the ViewModel's public API.
        savedState().set(KEY_INPUT, value);
        long now = System.currentTimeMillis();
        if (!value.isBlank() && now - lastTypingSentMs >= TYPING_SEND_INTERVAL_MS) {
            lastTypingSentMs = now;
            session.sendTyping(conversationId);
        }
    }

    public void onComposerDraftChange(MentionDraft draft) {
        MentionDraft safe = draft == null ? MentionDraft.empty("") : draft;
        savedStateHandle.set(KEY_MENTIONS, MessageMentionCodec.encode(safe.getMentions()));
        savedStateHandle.set(KEY_MENTION_ALL_OFFSET, safe.getMentionAllOffset());
        savedStateHandle.set(KEY_MENTION_ALL_LENGTH, safe.getMentionAllLength());
        onInputChange(safe.getContent());
    }

    private SavedStateHandle savedState() {
        return savedStateHandle;
    }

    /** Optimistic send — the outbox row renders SENDING immediately. */
    public void send() {
        UiState state = currentUiState();
        MentionDraft draft = getMentionDraft().trimmed();
        String text = draft.getContent();
        if (text.isEmpty()) {
            return;
        }
        ReplyTarget replyTarget = state.getReplyTarget();
        String replyToId = replyTarget == null ? null : replyTarget.getMessageId();
        savedState().set(KEY_INPUT, "");
        clearMentionDraft();
        updateUiState(current -> current.withInput("").withReplyTarget(null));
        io.reactivex.rxjava3.core.Completable send = draft.getMentions().isEmpty() && !draft.isMentionAll()
                ? messageRepository.send(conversationId, text, replyToId, MessageDto.TYPE_TEXT, List.of())
                : messageRepository.send(conversationId, text, replyToId, MessageDto.TYPE_TEXT,
                        List.of(), draft.getMentions(), draft.isMentionAll());
        disposables.add(send
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> { }, this::surfaceStreamFailure));
    }

    /** Tap a FAILED bubble to requeue it in the outbox. */
    public void retryFailed(String clientMessageId) {
        disposables.add(messageRepository.retry(clientMessageId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> { }, this::surfaceStreamFailure));
    }

    /** Loads a bounded history window before emitting the one-shot scroll target. */
    public void jumpToMessage(String messageId) {
        disposables.add(messageRepository.loadUntilLoaded(
                        conversationId, messageId, MessageRepository.MAX_JUMP_PAGES)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(jump -> {
                    if (jump == MessageJump.Found.INSTANCE) {
                        jumpTargets.emit(messageId);
                    } else if (jump == MessageJump.NotFound.INSTANCE) {
                        updateUiState(state -> state.withError(new ApiError.Unknown(ERROR_MESSAGE_GONE)));
                    } else if (jump instanceof MessageJump.Failed failed) {
                        updateUiState(state -> state.withError(failed.getError()));
                    }
                }, this::surfaceStreamFailure));
    }

    /** Server edit affordance: own, delivered, live TEXT messages only. */
    public boolean canEdit(ChatMessage message) {
        return message.getMine()
                && ChatBubbleRules.isMessageReplyable(message.getStatus())
                && !message.isDeleted()
                && MessageDto.TYPE_TEXT.equals(message.getType());
    }

    /** Delete is author-only but includes attachment messages. */
    public boolean canDelete(ChatMessage message) {
        return message.getMine()
                && ChatBubbleRules.isMessageReplyable(message.getStatus())
                && !message.isDeleted();
    }

    public void editMessage(String messageId, String newContent) {
        String trimmed = newContent.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        disposables.add(messageRepository.editMessage(conversationId, messageId, trimmed)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?>) {
                        actionEvents.emit(new ChatActionEvent.MessageEdited(messageId));
                    } else if (result instanceof Result.Failure<?> failure) {
                        updateUiState(state -> state.withError(failure.getError()));
                    }
                }, this::surfaceStreamFailure));
    }

    public void editMessage(String messageId, MentionDraft draft) {
        MentionDraft trimmed = draft == null ? MentionDraft.empty("") : draft.trimmed();
        if (trimmed.getContent().isEmpty()) return;
        disposables.add(messageRepository.editMessage(conversationId, messageId,
                        trimmed.getContent(), trimmed.getMentions(), trimmed.isMentionAll())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?>) {
                        actionEvents.emit(new ChatActionEvent.MessageEdited(messageId));
                    } else if (result instanceof Result.Failure<?> failure) {
                        updateUiState(state -> state.withError(failure.getError()));
                    }
                }, this::surfaceStreamFailure));
    }

    private void clearMentionDraft() {
        savedStateHandle.set(KEY_MENTIONS, "[]");
        savedStateHandle.set(KEY_MENTION_ALL_OFFSET, -1);
        savedStateHandle.set(KEY_MENTION_ALL_LENGTH, 0);
    }

    public void deleteMessage(String messageId) {
        disposables.add(messageRepository.deleteMessage(conversationId, messageId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Failure<?> failure) {
                        if (failure.getError() instanceof ApiError.Unknown unknown
                                && ERROR_RECALL_WINDOW_EXPIRED.equals(unknown.getCode())) {
                            updateUiState(state -> state.withError(
                                    new ApiError.Unknown(ERROR_RECALL_WINDOW_EXPIRED)));
                            disposables.add(messageRepository.refreshHistory(conversationId)
                                    .subscribeOn(Schedulers.io())
                                    .observeOn(AndroidSchedulers.mainThread())
                                    .subscribe(ignored -> {}, this::surfaceStreamFailure));
                        } else {
                            updateUiState(state -> state.withError(failure.getError()));
                        }
                    }
                }, this::surfaceStreamFailure));
    }

    public void deleteMessageForMe(String messageId) {
        disposables.add(messageRepository.hideMessageForMe(messageId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {}, this::surfaceStreamFailure));
    }

    public void forwardMessage(String messageId, String targetConversationId) {
        ChatMessage message = null;
        for (ChatMessage candidate : currentUiState().getMessages()) {
            if (candidate.getId().equals(messageId)) {
                message = candidate;
                break;
            }
        }
        if (message == null || !ChatBubbleRules.isMessageReplyable(message.getStatus())
                || !ForwardFilter.canForward(message.getType(), message.isDeleted())) {
            return;
        }
        String content = message.getContent() == null ? "" : message.getContent().trim();
        if (content.isEmpty()) {
            return;
        }
        disposables.add(messageRepository.send(
                        targetConversationId, content, null, MessageDto.TYPE_TEXT, List.of())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> actionEvents.emit(
                                new ChatActionEvent.MessageForwarded(targetConversationId)),
                        this::surfaceStreamFailure));
    }

    // ---- in-conversation search ----

    public void search(String query) {
        long generation = searchGeneration.incrementAndGet();
        searchDisposable.set(Disposable.empty());
        searchCursor = -1L;
        mutableSearchState.setValue(new ChatSearchState(query, false, List.of(), false, null));
        String trimmed = query.trim();
        if (trimmed.length() < MIN_SEARCH_CHARS) {
            return;
        }
        searchDisposable.set(Flowable.timer(
                        SEARCH_DEBOUNCE_MS, TimeUnit.MILLISECONDS, Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> performSearch(trimmed, null, false, generation),
                        this::setSearchError));
    }

    public void loadMoreSearch() {
        ChatSearchState state = currentSearchState();
        if (state.isLoading() || !state.getHasMore() || searchCursor < 0L) {
            return;
        }
        String query = state.getQuery().trim();
        if (query.length() < MIN_SEARCH_CHARS) {
            return;
        }
        long generation = searchGeneration.incrementAndGet();
        searchDisposable.set(Disposable.empty());
        performSearch(query, searchCursor, true, generation);
    }

    public void clearSearch() {
        searchGeneration.incrementAndGet();
        searchDisposable.set(Disposable.empty());
        searchCursor = -1L;
        mutableSearchState.setValue(new ChatSearchState());
    }

    private void performSearch(
            String query, @Nullable Long beforeSeq, boolean append, long generation) {
        updateSearchState(state -> state.withLoading(true, null));
        Single<Result<MessageSearchResult>> request = messageRepository.searchMessages(
                        conversationId, query, beforeSeq, SEARCH_PAGE_SIZE)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
        searchDisposable.set(request.subscribe(
                result -> {
                    if (searchGeneration.get() != generation
                            || !currentSearchState().getQuery().trim().equals(query)) {
                        return;
                    }
                    if (result instanceof Result.Success<?> success
                            && success.getValue() instanceof MessageSearchResult page) {
                        ChatSearchState current = currentSearchState();
                        List<ChatMessage> merged = append
                                ? mergeSearchResults(current.getResults(), page.getItems())
                                : page.getItems();
                        updateSearchState(state -> state.withResults(merged, page.getHasMore()));
                        Long next = page.getNextBeforeSeq();
                        if (next == null) {
                            next = minimumSequence(page.getItems());
                        }
                        searchCursor = next == null ? -1L : next;
                    } else if (result instanceof Result.Failure<?> failure) {
                        updateSearchState(state -> state.withError(failure.getError()));
                    }
                }, error -> {
                    if (searchGeneration.get() == generation) {
                        setSearchError(error);
                    }
                }));
    }

    private static List<ChatMessage> mergeSearchResults(
            List<ChatMessage> current, List<ChatMessage> page) {
        LinkedHashMap<String, ChatMessage> unique = new LinkedHashMap<>();
        for (ChatMessage message : current) {
            unique.putIfAbsent(message.getId(), message);
        }
        for (ChatMessage message : page) {
            unique.putIfAbsent(message.getId(), message);
        }
        return new ArrayList<>(unique.values());
    }

    @Nullable
    private static Long minimumSequence(List<ChatMessage> messages) {
        Long minimum = null;
        for (ChatMessage message : messages) {
            Long sequence = message.getSeq();
            if (sequence != null && (minimum == null || sequence < minimum)) {
                minimum = sequence;
            }
        }
        return minimum;
    }

    private void setSearchError(Throwable error) {
        updateSearchState(state -> state.withError(toApiError(error)));
    }

    // ---- reply, reactions, and pins ----

    public void setReplyTarget(ChatMessage message) {
        if (!ChatBubbleRules.isMessageReplyable(message.getStatus()) || message.isDeleted()) {
            return;
        }
        String snippet = message.getContent() == null ? message.getType() : message.getContent();
        updateUiState(state -> state.withReplyTarget(new ReplyTarget(
                message.getId(), message.getSenderDisplayName(), snippet)));
    }

    public void clearReplyTarget() {
        updateUiState(state -> state.withReplyTarget(null));
    }

    public void toggleReaction(ChatMessage message, String emoji) {
        if (!ChatBubbleRules.isMessageReplyable(message.getStatus()) || message.isDeleted()) {
            return;
        }
        disposables.add(messageRepository.toggleReaction(message.getId(), conversationId, emoji)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> { }, this::surfaceStreamFailure));
    }

    public void togglePin(ChatMessage message) {
        if (!ChatBubbleRules.isMessageReplyable(message.getStatus()) || message.isDeleted()) {
            return;
        }
        Completable operation = currentUiState().isPinned(message.getId())
                ? messageRepository.unpinMessage(conversationId, message.getId())
                : messageRepository.pinMessage(conversationId, message.getId());
        disposables.add(operation.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> { }, this::surfaceStreamFailure));
    }

    public void toggleConversationPin() {
        if (conversationId.isBlank()) return;
        boolean currentlyPinned = currentUiState().isConversationPinned();
        disposables.add(conversationRepository.setConversationPinned(conversationId, !currentlyPinned)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Failure<?> failure) {
                        updateUiState(state -> state.withError(failure.getError()));
                    }
                }, error -> updateUiState(state -> state.withError(new ApiError.Network(error.getMessage())))));
    }

    // ---- attachments ----

    public void sendAttachments(List<Uri> uris) {
        sendAttachments(uris, null);
    }

    public void sendAttachments(List<Uri> uris, @Nullable Long durationMs) {
        for (int index = 0; index < uris.size(); index++) {
            Uri uri = uris.get(index);
            Long attachmentDurationMs = index == 0 ? durationMs : null;
            String localId = UUID.randomUUID().toString();
            CompositeDisposable uploadTask = new CompositeDisposable();
            uploadDisposables.put(localId, uploadTask);
            disposables.add(uploadTask);
            Disposable describe = attachmentRepository.describe(uri)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(local -> startUpload(
                                    localId, uri, local, attachmentDurationMs, uploadTask),
                            error -> uploadDisposables.remove(localId));
            // CompositeDisposable preserves both handles when the description
            // completes synchronously before its subscription is returned.
            uploadTask.add(describe);
        }
    }

    private void startUpload(
            String localId,
            Uri uri,
            LocalAttachment local,
            @Nullable Long durationMs,
            CompositeDisposable uploadTask) {
        updatePending(rows -> {
            List<PendingAttachment> next = new ArrayList<>(rows);
            next.add(new PendingAttachment(
                    localId,
                    uri,
                    local.getDisplayName(),
                    local.getMimeType(),
                    local.getType(),
                    0f,
                    PendingAttachment.PendingState.UPLOADING));
            return next;
        });
        Flowable<AttachmentUploadEvent> uploads = durationMs == null
                ? attachmentRepository.upload(uri, local.getMimeType())
                : attachmentRepository.upload(uri, local.getMimeType(), durationMs);
        uploadTask.add(uploads.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(event -> onUploadEvent(localId, local, event),
                        error -> markUploadFailed(localId)));
    }

    private void onUploadEvent(String localId, LocalAttachment local, AttachmentUploadEvent event) {
        if (event instanceof AttachmentUploadEvent.Progress progress) {
            updatePending(rows -> mapPending(rows, localId, row -> new PendingAttachment(
                    row.getLocalId(), row.getUri(), row.getDisplayName(), row.getMimeType(),
                    row.getType(), progress.getFraction(), row.getState())));
        } else if (event instanceof AttachmentUploadEvent.Uploaded uploaded) {
            AttachmentDto attachment = uploaded.getAttachment();
            updatePending(rows -> filterPending(rows, localId));
            uploadDisposables.remove(localId);
            String content = local.getDisplayName();
            if (content == null || content.isBlank()) {
                content = attachment.getOriginalName();
            }
            if (content == null || content.isBlank()) {
                content = local.getType();
            }
            String mime = attachment.getMimeType();
            if (mime == null || mime.isBlank()) {
                mime = local.getMimeType();
            }
            String finalContent = content;
            String finalType = AttachmentDto.messageTypeForMime(mime);
            disposables.add(messageRepository.send(
                            conversationId,
                            finalContent,
                            null,
                            finalType,
                            List.of(attachment))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(() -> { }, this::surfaceStreamFailure));
        } else if (event instanceof AttachmentUploadEvent.Failed) {
            markUploadFailed(localId);
        }
    }

    private void markUploadFailed(String localId) {
        updatePending(rows -> mapPending(rows, localId, row -> new PendingAttachment(
                row.getLocalId(), row.getUri(), row.getDisplayName(), row.getMimeType(),
                row.getType(), row.getProgress(), PendingAttachment.PendingState.FAILED)));
        uploadDisposables.remove(localId);
    }

    public void removePendingAttachment(String localId) {
        CompositeDisposable task = uploadDisposables.remove(localId);
        if (task != null) {
            task.dispose();
        }
        updatePending(rows -> filterPending(rows, localId));
    }

    private static List<PendingAttachment> filterPending(
            List<PendingAttachment> rows, String localId) {
        List<PendingAttachment> next = new ArrayList<>();
        for (PendingAttachment row : rows) {
            if (!localId.equals(row.getLocalId())) {
                next.add(row);
            }
        }
        return next;
    }

    private static List<PendingAttachment> mapPending(
            List<PendingAttachment> rows,
            String localId,
            java.util.function.Function<PendingAttachment, PendingAttachment> mapper) {
        List<PendingAttachment> next = new ArrayList<>(rows.size());
        for (PendingAttachment row : rows) {
            next.add(localId.equals(row.getLocalId()) ? mapper.apply(row) : row);
        }
        return next;
    }

    private void updatePending(UnaryOperator<List<PendingAttachment>> transform) {
        updateUiState(state -> state.withPendingAttachments(transform.apply(state.getPendingAttachments())));
    }

    /** FILE bubble tap. The empty case represents a download failure. */
    public Maybe<File> attachmentFile(AttachmentDto attachment) {
        String id = attachment.getId();
        if (id == null) {
            return Maybe.empty();
        }
        return attachmentRepository.downloadToCache(id, attachment.getOriginalName())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .flatMapMaybe(result -> {
                    if (result instanceof Result.Success<?> success
                            && success.getValue() instanceof File file) {
                        return Maybe.just(file);
                    }
                    return Maybe.empty();
                });
    }

    /** Loads one older page using the oldest currently rendered sequence. */
    public void loadOlder() {
        if (loadingOlder) {
            return;
        }
        Long oldest = minimumSequence(currentUiState().getMessages());
        if (oldest == null) {
            return;
        }
        loadingOlder = true;
        disposables.add(messageRepository.refreshHistory(conversationId, oldest)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    loadingOlder = false;
                    if (result instanceof Result.Success<?> success
                            && success.getValue() instanceof com.nexachat.core.network.dto.MessagePageDto page) {
                        updateUiState(state -> state.withHasMore(page.getHasMore()));
                    } else if (result instanceof Result.Failure<?> failure) {
                        updateUiState(state -> state.withError(failure.getError()));
                    }
                }, error -> {
                    loadingOlder = false;
                    surfaceStreamFailure(error);
                }));
    }

    private UiState currentUiState() {
        UiState state = mutableUiState.getValue();
        return state == null ? new UiState() : state;
    }

    private ChatSearchState currentSearchState() {
        ChatSearchState state = mutableSearchState.getValue();
        return state == null ? new ChatSearchState() : state;
    }

    private void updateUiState(UnaryOperator<UiState> transform) {
        mutableUiState.setValue(transform.apply(currentUiState()));
    }

    private void updateSearchState(UnaryOperator<ChatSearchState> transform) {
        mutableSearchState.setValue(transform.apply(currentSearchState()));
    }

    private void surfaceStreamFailure(Throwable error) {
        updateUiState(state -> state.withError(toApiError(error)));
    }

    private static ApiError toApiError(Throwable error) {
        return new ApiError.Network(error.getMessage());
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        uploadDisposables.clear();
        if (typingTtlDisposable != null) {
            typingTtlDisposable.dispose();
        }
        messageRepository.closeConversation(conversationId);
        super.onCleared();
    }

    /** Reply draft preview; pending rows cannot be server reply targets. */
    public static final class ReplyTarget {
        private final String messageId;
        @Nullable private final String senderName;
        @Nullable private final String snippet;

        public ReplyTarget(String messageId, @Nullable String senderName, @Nullable String snippet) {
            this.messageId = Objects.requireNonNull(messageId, "messageId");
            this.senderName = senderName;
            this.snippet = snippet;
        }

        public String getMessageId() { return messageId; }
        @Nullable public String getSenderName() { return senderName; }
        @Nullable public String getSnippet() { return snippet; }

        @Override
        public boolean equals(Object other) {
            return other instanceof ReplyTarget that
                    && messageId.equals(that.messageId)
                    && Objects.equals(senderName, that.senderName)
                    && Objects.equals(snippet, that.snippet);
        }

        @Override
        public int hashCode() { return Objects.hash(messageId, senderName, snippet); }
    }

    /** Live state of the per-conversation AI opt-out control. */
    public static final class AiOptOutState {
        private final boolean optOut;
        private final boolean loading;
        private final boolean saving;
        @Nullable private final ApiError error;

        public AiOptOutState() {
            this(false, false, false, null);
        }

        private AiOptOutState(
                boolean optOut, boolean loading, boolean saving, @Nullable ApiError error) {
            this.optOut = optOut;
            this.loading = loading;
            this.saving = saving;
            this.error = error;
        }

        public boolean getOptOut() { return optOut; }
        public boolean isLoading() { return loading; }
        public boolean isSaving() { return saving; }
        public boolean isBusy() { return loading || saving; }
        @Nullable public ApiError getError() { return error; }

        private AiOptOutState withOptOut(boolean next) {
            return new AiOptOutState(next, loading, saving, error);
        }

        private AiOptOutState withLoading(boolean next) {
            return new AiOptOutState(optOut, next, saving, error);
        }

        private AiOptOutState withSaving(boolean next) {
            return new AiOptOutState(optOut, loading, next, error);
        }

        private AiOptOutState withError(@Nullable ApiError next) {
            return new AiOptOutState(optOut, loading, saving, next);
        }
    }

    /** Immutable chat render state. Lists are defensively copied at creation. */
    public static final class UiState {
        private final boolean loading;
        private final List<ChatMessage> messages;
        private final List<ChatMessage> sending;
        private final String input;
        private final boolean peerTyping;
        @Nullable private final String peerName;
        private final boolean group;
        private final boolean canPin;
        private final boolean connected;
        private final boolean hasMore;
        @Nullable private final ApiError error;
        private final List<PendingAttachment> pendingAttachments;
        @Nullable private final ReplyTarget replyTarget;
        private final List<ChatMessage> pins;
        private final long peerReadSeq;
        private final long peerDeliveredSeq;
        @Nullable private final PeerPresence peerPresence;
        @Nullable private final String otherUserId;
        private final boolean showSeen;
        private final boolean conversationPinned;

        public UiState() {
            this("");
        }

        private UiState(String input) {
            this(true, List.of(), List.of(), input, false, null, false, true, true,
                    false, null, List.of(), null, List.of(), 0L, 0L, null, null, true, false);
        }

        private UiState(
                boolean loading,
                List<ChatMessage> messages,
                List<ChatMessage> sending,
                String input,
                boolean peerTyping,
                @Nullable String peerName,
                boolean group,
                boolean canPin,
                boolean connected,
                boolean hasMore,
                @Nullable ApiError error,
                List<PendingAttachment> pendingAttachments,
                @Nullable ReplyTarget replyTarget,
                List<ChatMessage> pins,
                long peerReadSeq,
                long peerDeliveredSeq,
                @Nullable PeerPresence peerPresence,
                @Nullable String otherUserId,
                boolean showSeen,
                boolean conversationPinned) {
            this.loading = loading;
            this.messages = List.copyOf(messages);
            this.sending = List.copyOf(sending);
            this.input = Objects.requireNonNull(input, "input");
            this.peerTyping = peerTyping;
            this.peerName = peerName;
            this.group = group;
            this.canPin = canPin;
            this.connected = connected;
            this.hasMore = hasMore;
            this.error = error;
            this.pendingAttachments = List.copyOf(pendingAttachments);
            this.replyTarget = replyTarget;
            this.pins = List.copyOf(pins);
            this.peerReadSeq = peerReadSeq;
            this.peerDeliveredSeq = peerDeliveredSeq;
            this.peerPresence = peerPresence;
            this.otherUserId = otherUserId;
            this.showSeen = showSeen;
            this.conversationPinned = conversationPinned;
        }

        public boolean isLoading() { return loading; }
        public boolean getLoading() { return loading; }
        public List<ChatMessage> getMessages() { return messages; }
        public List<ChatMessage> getSending() { return sending; }
        public String getInput() { return input; }
        public boolean isPeerTyping() { return peerTyping; }
        public boolean getPeerTyping() { return peerTyping; }
        @Nullable public String getPeerName() { return peerName; }
        public boolean isGroup() { return group; }
        public boolean getIsGroup() { return group; }
        public boolean isCanPin() { return canPin; }
        public boolean getCanPin() { return canPin; }
        public boolean isConnected() { return connected; }
        public boolean getConnected() { return connected; }
        public boolean isHasMore() { return hasMore; }
        public boolean getHasMore() { return hasMore; }
        @Nullable public ApiError getError() { return error; }
        public List<PendingAttachment> getPendingAttachments() { return pendingAttachments; }
        @Nullable public ReplyTarget getReplyTarget() { return replyTarget; }
        public List<ChatMessage> getPins() { return pins; }
        public long getPeerReadSeq() { return peerReadSeq; }
        public long getPeerDeliveredSeq() { return peerDeliveredSeq; }
        @Nullable public PeerPresence getPeerPresence() { return peerPresence; }
        @Nullable public String getOtherUserId() { return otherUserId; }
        public boolean isShowSeen() { return showSeen; }
        public boolean isConversationPinned() { return conversationPinned; }

        public boolean isPinned(String messageId) {
            for (ChatMessage pin : pins) {
                if (pin.getId().equals(messageId)) {
                    return true;
                }
            }
            return false;
        }

        private UiState withMessages(List<ChatMessage> nextMessages) {
            List<ChatMessage> pending = nextMessages.stream()
                    .filter(message -> message.getStatus() != DeliveryStatus.SENT)
                    .collect(java.util.stream.Collectors.toList());
            return copy(loading, nextMessages, pending, input, peerTyping, peerName, group,
                    canPin, connected, hasMore, error, pendingAttachments, replyTarget, pins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    showSeen, conversationPinned);
        }

        private UiState withConversation(ConversationSummary conversation) {
            String displayName = conversation.getDisplayName();
            return copy(loading, messages, sending, input, peerTyping,
                    displayName == null || displayName.isBlank() ? null : displayName,
                    conversation.isGroup(),
                    !conversation.isGroup() || "ADMIN".equals(conversation.getMyRole()),
                    connected, hasMore, error, pendingAttachments, replyTarget, pins,
                    conversation.getPeerReadSeq(), conversation.getPeerDeliveredSeq(),
                    conversation.getPeerPresence(),
                    blankToNull(conversation.getOtherUserId()),
                    showSeen,
                    conversation.isPinned());
        }

        private UiState withInput(String nextInput) {
            return copy(loading, messages, sending, nextInput, peerTyping, peerName, group,
                    canPin, connected, hasMore, error, pendingAttachments, replyTarget, pins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    showSeen, conversationPinned);
        }

        private UiState withPeerTyping(boolean nextPeerTyping) {
            return copy(loading, messages, sending, input, nextPeerTyping, peerName, group,
                    canPin, connected, hasMore, error, pendingAttachments, replyTarget, pins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    showSeen, conversationPinned);
        }

        private UiState withConnected(boolean nextConnected) {
            return copy(loading, messages, sending, input, peerTyping, peerName, group,
                    canPin, nextConnected, hasMore, error, pendingAttachments, replyTarget, pins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    showSeen, conversationPinned);
        }

        private UiState withHasMore(boolean nextHasMore) {
            return copy(loading, messages, sending, input, peerTyping, peerName, group,
                    canPin, connected, nextHasMore, error, pendingAttachments, replyTarget, pins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    showSeen, conversationPinned);
        }

        private UiState withLoading(boolean nextLoading) {
            return copy(nextLoading, messages, sending, input, peerTyping, peerName, group,
                    canPin, connected, hasMore, error, pendingAttachments, replyTarget, pins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    showSeen, conversationPinned);
        }

        private UiState withError(@Nullable ApiError nextError) {
            return copy(loading, messages, sending, input, peerTyping, peerName, group,
                    canPin, connected, hasMore, nextError, pendingAttachments, replyTarget, pins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    showSeen, conversationPinned);
        }

        private UiState withPendingAttachments(List<PendingAttachment> pending) {
            return copy(loading, messages, sending, input, peerTyping, peerName, group,
                    canPin, connected, hasMore, error, pending, replyTarget, pins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    showSeen, conversationPinned);
        }

        private UiState withReplyTarget(@Nullable ReplyTarget target) {
            return copy(loading, messages, sending, input, peerTyping, peerName, group,
                    canPin, connected, hasMore, error, pendingAttachments, target, pins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    showSeen, conversationPinned);
        }

        private UiState withPins(List<ChatMessage> nextPins) {
            return copy(loading, messages, sending, input, peerTyping, peerName, group,
                    canPin, connected, hasMore, error, pendingAttachments, replyTarget, nextPins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    showSeen, conversationPinned);
        }

        private UiState withShowSeen(boolean nextShowSeen) {
            return copy(loading, messages, sending, input, peerTyping, peerName, group,
                    canPin, connected, hasMore, error, pendingAttachments, replyTarget, pins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    nextShowSeen, conversationPinned);
        }

        private static UiState copy(
                boolean loading,
                List<ChatMessage> messages,
                List<ChatMessage> sending,
                String input,
                boolean peerTyping,
                @Nullable String peerName,
                boolean group,
                boolean canPin,
                boolean connected,
                boolean hasMore,
                @Nullable ApiError error,
                List<PendingAttachment> pendingAttachments,
                @Nullable ReplyTarget replyTarget,
                List<ChatMessage> pins,
                long peerReadSeq,
                long peerDeliveredSeq,
                @Nullable PeerPresence peerPresence,
                @Nullable String otherUserId,
                boolean showSeen,
                boolean conversationPinned) {
            return new UiState(loading, messages, sending, input, peerTyping, peerName, group,
                    canPin, connected, hasMore, error, pendingAttachments, replyTarget, pins,
                    peerReadSeq, peerDeliveredSeq, peerPresence, otherUserId,
                    showSeen, conversationPinned);
        }

        @Nullable
        private static String blankToNull(@Nullable String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}

/** LiveData event wrapper: an event is consumed once, so a new observer after rotation sees no replay. */
final class ChatSingleLiveEvent<T> extends MutableLiveData<ChatSingleLiveEvent.Event<T>> {
    private long sequence;

    void emit(T value) {
        setValue(new Event<>(++sequence, value));
    }

    static final class Event<T> {
        private final long sequence;
        private final T value;
        private boolean consumed;

        Event(long sequence, T value) {
            this.sequence = sequence;
            this.value = value;
        }

        long getSequence() {
            return sequence;
        }

        @Nullable
        synchronized T consume() {
            if (consumed) {
                return null;
            }
            consumed = true;
            return value;
        }
    }
}
