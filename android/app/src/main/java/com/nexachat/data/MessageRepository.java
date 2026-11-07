package com.nexachat.data;

import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.database.MessageReactionEntity;
import com.nexachat.core.database.OutboxDao;
import com.nexachat.core.database.OutboxEntity;
import com.nexachat.core.database.OutboxState;
import com.nexachat.core.database.PinDao;
import com.nexachat.core.database.PinnedMessageEntity;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.ApiResultKt;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.EditMessageRequest;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.MessagePageDto;
import com.nexachat.core.network.dto.PinRequest;
import com.nexachat.core.network.dto.ReactionRequest;
import com.nexachat.core.network.dto.ReactionSummaryDto;
import com.nexachat.core.network.dto.ReadRequest;
import com.nexachat.core.network.dto.SendMessageRequest;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.exceptions.Exceptions;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.nexachat.core.common.Unit;
import okhttp3.ResponseBody;
import retrofit2.Call;

/**
 * The message pipeline: {@code send()} writes an outbox row as SENDING, sends
 * over STOMP, and acknowledges by {@code clientMessageId}; the REST fallback
 * follows the same acknowledgement path. Rejections become FAILED rows, while
 * rate limits and transport failures retain SENDING for a later retry.
 *
 * <p>Room is the only render source. Realtime events are written through and
 * deduplicated by message id and {@code clientMessageId}, so an event is never
 * rendered independently of Room. This ordering is required for reconnect
 * resync and for deduplicating REST and realtime acknowledgements.
 *
 * <p>Database and network work runs on {@link Schedulers#io()}. Public
 * operations return cold Rx types so callers own and can cancel each request.
 * The repository owns its long-lived event collectors and releases them from
 * {@link #dispose()}.
 */
@Singleton
public final class MessageRepository {
    /** Maximum buffered typing, read-receipt, and interaction events per subscriber. */
    public static final int EVENT_BUFFER = 32;
    /** Backward history page budget; approximately 600 messages at the default page size. */
    public static final int MAX_JUMP_PAGES = 20;
    /** Minimum query length accepted by the server search endpoint. */
    public static final int MIN_SEARCH_CHARS = 2;
    /** Maximum result count accepted by the server search endpoint. */
    public static final int MAX_SEARCH_LIMIT = 50;
    public static final String ERROR_MESSAGE_NOT_FOUND = "MESSAGE_NOT_FOUND";
    public static final String ERROR_MESSAGE_DELETED = "MESSAGE_DELETED";
    public static final String ERROR_NOT_MESSAGE_AUTHOR = "NOT_MESSAGE_AUTHOR";
    public static final String ERROR_VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String ERROR_RATE_LIMITED = "RATE_LIMITED";
    public static final long RATE_LIMIT_RETRY_DELAY_MS = 10_000L;
    /** Envelope keys that may wrap the id list returned by {@code GET /pins}. */
    private static final List<String> PIN_LIST_KEYS =
            List.of("items", "pins", "messageIds", "pinnedMessageIds");

    private final MessageApi messageApi;
    private final MessageDao messageDao;
    private final ConversationDao conversationDao;
    private final OutboxDao outboxDao;
    private final ReactionDao reactionDao;
    private final PinDao pinDao;
    private final RealtimeSession session;
    private final TokenStore tokenStore;
    private final SessionEvents sessionEvents;
    private final Moshi moshi;
    private final JsonAdapter<List<AttachmentDto>> attachmentsAdapter;
    /** Reads loose JSON as maps, lists, and scalar values for the pin endpoint. */
    private final JsonAdapter<Object> anyAdapter;
    /** Reads embedded {@code message} objects inside GET /pins entries. */
    private final JsonAdapter<MessageDto> messageAdapter;
    private final Scheduler timerScheduler;
    private final CompositeDisposable subscriptions = new CompositeDisposable();
    /** Conversation currently visible; its incoming messages must not bump unread count. */
    private final AtomicReference<String> activeConversation = new AtomicReference<>();
    /** Server-confirmed read watermark per conversation, used to deduplicate POST /read. */
    private final ConcurrentHashMap<String, Long> lastMarkedRead = new ConcurrentHashMap<>();
    /** A delete broadcast racing an optimistic DELETE must win over its rollback. */
    private final java.util.Set<String> pendingDeleteIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> confirmedDeleteIds = ConcurrentHashMap.newKeySet();
    /** Coalesces RATE_LIMITED responses into one delayed outbox flush. */
    private final Object rateLimitRetryLock = new Object();
    private final AtomicLong lastOutboxCreatedAt = new AtomicLong();
    private final FlowableProcessor<TypingEvent> typingEvents = PublishProcessor.<TypingEvent>create().toSerialized();
    private final FlowableProcessor<ReadEvent> readEvents = PublishProcessor.<ReadEvent>create().toSerialized();
    /** Reaction and pin action failures surfaced to the screen after Room rollback. */
    private final FlowableProcessor<ApiError> interactionEvents = PublishProcessor.<ApiError>create().toSerialized();
    @Nullable private volatile Disposable rateLimitRetry;
    @Nullable private volatile TokenUser tokenToUser;

    @Inject
    public MessageRepository(MessageApi messageApi, MessageDao messageDao,
            ConversationDao conversationDao, OutboxDao outboxDao, ReactionDao reactionDao,
            PinDao pinDao, RealtimeSession session, TokenStore tokenStore,
            SessionEvents sessionEvents, Moshi moshi) {
        this(messageApi, messageDao, conversationDao, outboxDao, reactionDao, pinDao,
                session, tokenStore, sessionEvents, moshi, Schedulers.computation());
    }

    /** Package constructor lets deterministic Rx tests control the rate-limit clock. */
    MessageRepository(MessageApi messageApi, MessageDao messageDao,
            ConversationDao conversationDao, OutboxDao outboxDao, ReactionDao reactionDao,
            PinDao pinDao, RealtimeSession session, TokenStore tokenStore,
            SessionEvents sessionEvents, Moshi moshi, Scheduler timerScheduler) {
        this.messageApi = messageApi;
        this.messageDao = messageDao;
        this.conversationDao = conversationDao;
        this.outboxDao = outboxDao;
        this.reactionDao = reactionDao;
        this.pinDao = pinDao;
        this.session = session;
        this.tokenStore = tokenStore;
        this.sessionEvents = sessionEvents;
        this.moshi = moshi;
        this.timerScheduler = timerScheduler;
        this.attachmentsAdapter = moshi.adapter(Types.newParameterizedType(List.class, AttachmentDto.class));
        this.anyAdapter = moshi.adapter(Object.class);
        this.messageAdapter = moshi.adapter(MessageDto.class);
        collectInBackground(session.getEvents().concatMapCompletable(this::routeSessionEvent));
        collectInBackground(session.getResyncRequests().concatMapCompletable(ignored -> resyncOnReconnect()));
        collectInBackground(sessionEvents.getEvents()
                .filter(event -> event instanceof SessionEvents.Event.LoggedOut)
                .concatMapCompletable(ignored -> wipeOnLogout()));
    }

    public Flowable<TypingEvent> getTypingEvents() {
        return typingEvents.onBackpressureBuffer(EVENT_BUFFER, () -> {},
                io.reactivex.rxjava3.core.BackpressureOverflowStrategy.DROP_OLDEST);
    }

    public Flowable<ReadEvent> getReadEvents() {
        return readEvents.onBackpressureBuffer(EVENT_BUFFER, () -> {},
                io.reactivex.rxjava3.core.BackpressureOverflowStrategy.DROP_OLDEST);
    }

    public Flowable<ApiError> getInteractionEvents() {
        return interactionEvents.onBackpressureBuffer(EVENT_BUFFER, () -> {},
                io.reactivex.rxjava3.core.BackpressureOverflowStrategy.DROP_OLDEST);
    }

    /**
     * Room messages plus pending outbox rows. Sent rows sort by sequence and
     * pending rows trail in FIFO order so retries return to the bottom.
     */
    public Flowable<List<ChatMessage>> messagesFlow(String conversationId) {
        return Flowable.combineLatest(messageDao.messagesForConversation(conversationId),
                outboxDao.pendingOrdered(), reactionDao.reactionsForConversation(conversationId),
                (messages, pending, reactions) -> new MessageSnapshot(messages, pending, reactions))
                .concatMapSingle(snapshot -> currentUserId().map(Optional::of)
                        .defaultIfEmpty(Optional.empty()).map(myId -> {
                    Map<String, List<ReactionChip>> chips = new java.util.HashMap<>();
                    for (MessageReactionEntity reaction : snapshot.reactions) {
                        chips.computeIfAbsent(reaction.getMessageId(), ignored -> new ArrayList<>())
                                .add(toChip(reaction));
                    }
                    List<ChatMessage> result = new ArrayList<>(snapshot.messages.size() + snapshot.pending.size());
                    for (MessageEntity message : snapshot.messages) {
                        result.add(toChatMessage(message, myId.orElse(null),
                                chips.getOrDefault(message.getId(), Collections.emptyList())));
                    }
                    snapshot.pending.stream().filter(item -> conversationId.equals(item.getConversationId()))
                            .map(this::toChatMessage).forEach(result::add);
                    return result;
                })).subscribeOn(Schedulers.io());
    }

    /**
     * Pinned messages for the banner, newest pin first. Messages outside local
     * history still appear as id-only stubs so the banner count matches server state.
     */
    public Flowable<List<ChatMessage>> pinsFlow(String conversationId) {
        return Flowable.combineLatest(pinDao.pinnedFor(conversationId),
                messageDao.messagesForConversation(conversationId),
                (pins, messages) -> new PinSnapshot(pins, messages))
                .concatMapSingle(snapshot -> currentUserId().map(Optional::of)
                        .defaultIfEmpty(Optional.empty()).map(myId -> {
                    Map<String, MessageEntity> byId = new java.util.HashMap<>();
                    for (MessageEntity message : snapshot.messages) byId.put(message.getId(), message);
                    List<ChatMessage> result = new ArrayList<>(snapshot.pins.size());
                    for (PinnedMessageEntity pin : snapshot.pins) {
                        MessageEntity stored = byId.get(pin.getMessageId());
                        result.add(stored == null ? pinStub(pin) : toChatMessage(stored, myId.orElse(null),
                                Collections.emptyList()));
                    }
                    return result;
                })).subscribeOn(Schedulers.io());
    }

    /**
     * Marks the conversation visible, watches its topic, marks stored history
     * read, and starts the outbox. History paging remains separate so callers
     * can observe the returned {@code hasMore} value.
     */
    public Completable openConversation(String conversationId) {
        return Completable.fromAction(() -> {
                    activeConversation.set(conversationId);
                    session.ensureConnected();
                    session.watchConversation(conversationId);
                }).andThen(resyncForward(conversationId))
                .andThen(messageDao.maxSeq(conversationId).flatMapCompletable(seq -> sendRead(conversationId, seq)))
                .andThen(flushOutbox())
                .andThen(refreshPins(conversationId).ignoreElement())
                .subscribeOn(Schedulers.io());
    }

    public void closeConversation(String conversationId) {
        if (Objects.equals(activeConversation.get(), conversationId)) activeConversation.set(null);
        session.unwatchConversation(conversationId);
    }

    public Single<Result<MessagePageDto>> refreshHistory(String conversationId) {
        return refreshHistory(conversationId, null);
    }

    /** Fetches one history page and writes it through to Room. */
    public Single<Result<MessagePageDto>> refreshHistory(String conversationId, @Nullable Long beforeSeq) {
        return call(messageApi.getMessages(conversationId, beforeSeq, null, MessageApi.DEFAULT_PAGE_SIZE))
                .flatMap(result -> {
                    if (result instanceof Result.Success<?> success) {
                        MessagePageDto page = (MessagePageDto) success.getValue();
                        return storeHistoryItems(conversationId, page.getItems())
                                .andThen(Single.just(result));
                    }
                    return Single.just(result);
                }).subscribeOn(Schedulers.io());
    }

    public Single<MessageJump> loadUntilLoaded(String conversationId, String messageId) {
        return loadUntilLoaded(conversationId, messageId, MAX_JUMP_PAGES);
    }

    /**
     * Pages backward until the target is stored so a quote or pin can scroll
     * outside the currently loaded window. Each page uses the regular
     * write-through path and is bounded by {@code maxPages}.
     */
    public Single<MessageJump> loadUntilLoaded(String conversationId, String messageId, int maxPages) {
        return messageDao.exists(messageId).flatMap(found -> {
            if (found) return Single.just(MessageJump.Found);
            return messageDao.minSeq(conversationId).flatMap(min -> {
                if (min > 0L) return loadOlderPages(conversationId, messageId, maxPages);
                return refreshHistory(conversationId).flatMap(first -> {
                    if (first instanceof Result.Failure<?> failure) {
                        return Single.just(new MessageJump.Failed(failure.getError()));
                    }
                    return messageDao.exists(messageId).flatMap(nowFound -> nowFound
                            ? Single.just(MessageJump.Found)
                            : loadOlderPages(conversationId, messageId, maxPages));
                });
            });
        }).subscribeOn(Schedulers.io());
    }

    private Single<MessageJump> loadOlderPages(String conversationId, String messageId, int pagesLeft) {
        if (pagesLeft <= 0) return Single.just(MessageJump.NotFound);
        return messageDao.minSeq(conversationId).flatMap(oldest -> {
            if (oldest <= 0L) return Single.just(MessageJump.NotFound);
            return refreshHistory(conversationId, oldest).flatMap(pageResult -> {
                if (pageResult instanceof Result.Failure<?> failure) {
                    return Single.just(new MessageJump.Failed(failure.getError()));
                }
                MessagePageDto page = (MessagePageDto) ((Result.Success<?>) pageResult).getValue();
                return messageDao.exists(messageId).flatMap(found -> {
                    if (found) return Single.just(MessageJump.Found);
                    if (!page.getHasMore()) return Single.just(MessageJump.NotFound);
                    return loadOlderPages(conversationId, messageId, pagesLeft - 1);
                });
            });
        });
    }

    /**
     * Shared write-through for history and reconnect resync. Message rows
     * upsert by id. Reaction summaries replace stored rows only when the DTO
     * actually contains the field; absent ({@code null}) summaries preserve
     * local reaction state.
     */
    private Completable storeHistoryItems(String conversationId, List<MessageDto> items) {
        List<MessageEntity> deletedEntities = new ArrayList<>();
        Completable writes = Flowable.fromIterable(items).concatMapCompletable(dto -> {
            MessageEntity entity = toEntity(dto, conversationId);
            if (entity == null) return Completable.complete();
            return messageDao.byId(entity.getId()).map(Optional::of).defaultIfEmpty(Optional.empty())
                    .flatMapCompletable(existingOpt -> {
                        Completable write = Completable.complete();
                        if (existingOpt.isEmpty()) {
                            write = messageDao.upsert(entity);
                        } else {
                            MessageEntity existing = existingOpt.get();
                            if (existing.getDeletedAtEpochMs() == null && entity.getDeletedAtEpochMs() != null) {
                                write = messageDao.update(copyMessage(existing, "", null,
                                        existing.getEditedAtEpochMs(), entity.getDeletedAtEpochMs(),
                                        existing.getReplyToSnippet()));
                            } else if (existing.getDeletedAtEpochMs() == null
                                    && (!Objects.equals(existing.getContent(), entity.getContent())
                                    || !Objects.equals(existing.getEditedAtEpochMs(), entity.getEditedAtEpochMs()))) {
                                write = messageDao.update(copyMessage(existing, entity.getContent(),
                                        existing.getAttachmentsJson(), entity.getEditedAtEpochMs() != null
                                                ? entity.getEditedAtEpochMs() : existing.getEditedAtEpochMs(),
                                        existing.getDeletedAtEpochMs(), existing.getReplyToSnippet()));
                            }
                        }
                        if (entity.getDeletedAtEpochMs() != null) {
                            deletedEntities.add(entity);
                                    write = write.andThen(conversationDao.byId(entity.getConversationId())
                                    .flatMapCompletable(row -> Objects.equals(row.getLastMessageSeq(), entity.getSeq())
                                            ? conversationDao.markLastMessageDeleted(entity.getConversationId())
                                            : Completable.complete()));
                        }
                        return write;
                    });
        });
        Completable cleanup = Flowable.fromIterable(deletedEntities).concatMapCompletable(entity ->
                reactionDao.deleteForMessage(entity.getId())
                        .andThen(pinDao.delete(entity.getConversationId(), entity.getId()))
                        .andThen(messageDao.clearReplySnippets(entity.getId())));
        return writes.andThen(cleanup).andThen(currentUserId().map(Optional::of)
                .defaultIfEmpty(Optional.empty()).flatMapCompletable(myIdOpt -> {
                    List<String> owners = new ArrayList<>();
                    List<MessageReactionEntity> rows = new ArrayList<>();
                    Completable collect = Flowable.fromIterable(items).concatMapMaybe(dto -> {
                        if (dto.getDeletedAt() != null && !dto.getDeletedAt().isBlank()) return Maybe.empty();
                        List<ReactionSummaryDto> summaries = dto.getReactions();
                        if (summaries == null) return Maybe.empty();
                        return messageDao.byId(dto.getId()).filter(row -> row.getDeletedAtEpochMs() == null)
                                .map(row -> new HistoryReactions(dto, row));
                    }).doOnNext(entry -> {
                        owners.add(entry.dto.getId());
                        String resolvedConversationId = entry.dto.getConversationId() != null
                                ? entry.dto.getConversationId() : conversationId;
                        for (ReactionSummaryDto summary : entry.dto.getReactions()) {
                            MessageReactionEntity reaction = toReactionEntity(summary, entry.dto.getId(),
                                    resolvedConversationId, myIdOpt.orElse(null));
                            if (reaction != null) rows.add(reaction);
                        }
                    }).ignoreElements();
                    return collect.andThen(Completable.defer(() -> reactionDao.replaceForMessages(owners, rows)));
                }));
    }

    /**
     * Edits an own TEXT message optimistically, then PATCHes the server. On
     * failure, restores the exact old row only if no newer server write landed.
     * Blank and identical content are no-ops and never reach the network.
     */
    public Single<Result<Unit>> editMessage(String conversationId, String messageId, String newContent) {
        String trimmed = newContent.trim();
        if (trimmed.isEmpty()) return Single.just(Result.failure(new ApiError.Unknown(ERROR_VALIDATION_FAILED)));
        AtomicReference<MessageEntity> optimisticRef = new AtomicReference<>();
        AtomicReference<MessageEntity> beforeRef = new AtomicReference<>();
        return messageDao.byId(messageId).map(Optional::of).defaultIfEmpty(Optional.empty()).<Result<Unit>>flatMap(beforeOpt -> {
            if (beforeOpt.isEmpty()) return Single.just(Result.failure(new ApiError.Unknown(ERROR_MESSAGE_NOT_FOUND)));
            MessageEntity before = beforeOpt.get();
            beforeRef.set(before);
            return isMine(before.getSenderId()).<Result<Unit>>flatMap(mine -> {
                if (!mine) return Single.just(Result.failure(new ApiError.Unknown(ERROR_NOT_MESSAGE_AUTHOR)));
                if (!MessageDto.TYPE_TEXT.equals(before.getType())) {
                    return Single.just(Result.failure(new ApiError.Unknown(ERROR_VALIDATION_FAILED)));
                }
                if (before.getDeletedAtEpochMs() != null) {
                    return Single.just(Result.failure(new ApiError.Unknown(ERROR_MESSAGE_DELETED)));
                }
                if (Objects.equals(before.getContent(), trimmed)) return Single.just(new Result.Success<>(Unit.INSTANCE));
                MessageEntity optimistic = copyMessage(before, trimmed, before.getAttachmentsJson(),
                        System.currentTimeMillis(), before.getDeletedAtEpochMs(), before.getReplyToSnippet());
                optimisticRef.set(optimistic);
                return messageDao.update(optimistic).andThen(call(messageApi.editMessage(conversationId,
                                messageId, new EditMessageRequest(trimmed))))
                        .<Result<Unit>>flatMap(result -> {
                            if (result instanceof Result.Success<?> success) {
                                MessageDto dto = (MessageDto) success.getValue();
                                MessageEntity server = toEntity(dto, conversationId);
                                if (server == null || server.getDeletedAtEpochMs() != null) {
                                    return Single.just(new Result.Success<>(Unit.INSTANCE));
                                }
                                MessageEntity authoritative = copyMessage(server, server.getContent(),
                                        server.getAttachmentsJson(), server.getEditedAtEpochMs() != null
                                                ? server.getEditedAtEpochMs() : optimistic.getEditedAtEpochMs(),
                                        server.getDeletedAtEpochMs(), server.getReplyToSnippet());
                                return messageDao.byId(messageId).map(Optional::of).defaultIfEmpty(Optional.empty())
                                        .<Result<Unit>>flatMap(current -> {
                                            if (current.isEmpty() || !sameMessage(current.get(), optimistic)) {
                                                return Single.just(new Result.Success<>(Unit.INSTANCE));
                                            }
                                            return messageDao.update(authoritative)
                                                    .andThen(refreshLastMessagePreview(authoritative))
                                                    .andThen(Single.just(new Result.Success<>(Unit.INSTANCE)));
                                        });
                            }
                            return rollbackEdit(messageId, before, optimistic)
                                    .andThen(Single.just(Result.cast(result)));
                        });
            });
        }).onErrorResumeNext(error -> {
            MessageEntity optimistic = optimisticRef.get();
            MessageEntity before = beforeRef.get();
            Completable rollback = optimistic != null && before != null
                    ? rollbackEdit(messageId, before, optimistic) : Completable.complete();
            return rollback.andThen(Single.error(error));
        }).doOnDispose(() -> {
            MessageEntity optimistic = optimisticRef.get();
            MessageEntity before = beforeRef.get();
            if (optimistic != null && before != null) scheduleDetached(rollbackEdit(messageId, before, optimistic));
        }).subscribeOn(Schedulers.io());
    }

    private Completable rollbackEdit(String messageId, MessageEntity before, MessageEntity optimistic) {
        return messageDao.byId(messageId).flatMapCompletable(current -> sameMessage(current, optimistic)
                ? messageDao.update(before) : Completable.complete());
    }

    /**
     * Deletes a message for everyone. The optimistic tombstone clears content,
     * attachments, reactions, and pins; a failed request restores the prior
     * state only if no newer update replaced it. A concurrent delete event wins.
     */
    public Single<Result<Unit>> deleteMessage(String conversationId, String messageId) {
        return Single.defer(() -> {
            if (!pendingDeleteIds.add(messageId)) return Single.just(new Result.Success<>(Unit.INSTANCE));
            confirmedDeleteIds.remove(messageId);
            AtomicReference<DeleteRollback> rollbackRef = new AtomicReference<>();
            Single<Result<Unit>> operation = messageDao.byId(messageId).map(Optional::of)
                .defaultIfEmpty(Optional.empty()).flatMap(beforeOpt -> {
                    if (beforeOpt.isEmpty()) return Single.just(Result.failure(new ApiError.Unknown(ERROR_MESSAGE_NOT_FOUND)));
                    MessageEntity before = beforeOpt.get();
                    return isMine(before.getSenderId()).flatMap(mine -> {
                        if (!mine) return Single.just(Result.failure(new ApiError.Unknown(ERROR_NOT_MESSAGE_AUTHOR)));
                        if (before.getDeletedAtEpochMs() != null) return Single.just(new Result.Success<>(Unit.INSTANCE));
                        return reactionDao.forMessage(messageId).flatMap(reactions ->
                                pinDao.find(conversationId, messageId).map(Optional::of)
                                        .defaultIfEmpty(Optional.empty()).flatMap(pinOpt ->
                                                isLastMessage(before).flatMap(wasLast -> {
                                                    MessageEntity optimistic = copyMessage(before, "", null,
                                                            before.getEditedAtEpochMs(), System.currentTimeMillis(),
                                                            before.getReplyToSnippet());
                                                    DeleteRollback state = new DeleteRollback(before, optimistic,
                                                            reactions, pinOpt.orElse(null), wasLast);
                                                    rollbackRef.set(state);
                                                    Completable prepare = messageDao.update(optimistic)
                                                            .andThen(reactionDao.deleteForMessage(messageId))
                                                            .andThen(pinDao.delete(conversationId, messageId));
                                                    if (wasLast) prepare = prepare.andThen(
                                                            conversationDao.markLastMessageDeleted(conversationId));
                                                    return prepare.andThen(call(messageApi.deleteMessage(conversationId, messageId)))
                                                            .flatMap(result -> {
                                                                if (result instanceof Result.Success<?>) {
                                                                    return messageDao.clearReplySnippets(messageId)
                                                                            .andThen(Single.just(new Result.Success<>(Unit.INSTANCE)));
                                                                }
                                                                return (confirmedDeleteIds.contains(messageId)
                                                                        ? Completable.complete()
                                                                        : rollbackDeleteIfUnchanged(conversationId, state))
                                                                        .andThen(Single.just(Result.cast(result)));
                                                            });
                                                })));
                    });
                });
        return operation.onErrorResumeNext(error -> {
                    DeleteRollback state = rollbackRef.get();
                    Completable rollback = state != null && !confirmedDeleteIds.contains(messageId)
                            ? rollbackDeleteIfUnchanged(conversationId, state) : Completable.complete();
                    return rollback.andThen(Single.error(error));
                }).doOnDispose(() -> {
                    DeleteRollback state = rollbackRef.get();
                    if (state == null) {
                        pendingDeleteIds.remove(messageId);
                        confirmedDeleteIds.remove(messageId);
                    } else {
                        Completable cleanup = Completable.defer(() -> confirmedDeleteIds.contains(messageId)
                                        ? Completable.complete() : rollbackDeleteIfUnchanged(conversationId, state))
                                .doFinally(() -> {
                                    pendingDeleteIds.remove(messageId);
                                    confirmedDeleteIds.remove(messageId);
                                });
                        scheduleDetached(cleanup);
                    }
                })
                .doOnSuccess(ignored -> clearDeleteState(messageId))
                .doOnError(ignored -> clearDeleteState(messageId))
                .subscribeOn(Schedulers.io());
        });
    }

    private void clearDeleteState(String messageId) {
        pendingDeleteIds.remove(messageId);
        confirmedDeleteIds.remove(messageId);
    }

    private Completable rollbackDeleteIfUnchanged(String conversationId, DeleteRollback state) {
        return messageDao.byId(state.before.getId()).flatMapCompletable(current -> {
            if (!sameMessage(current, state.optimistic)) return Completable.complete();
            Completable restore = messageDao.update(state.before)
                    .andThen(reactionDao.forMessage(state.before.getId()).flatMapCompletable(existing ->
                            existing.isEmpty() ? reactionDao.upsertAll(state.reactions) : Completable.complete()));
            if (state.pin != null) {
                restore = restore.andThen(pinDao.find(conversationId, state.before.getId()).isEmpty()
                        .flatMapCompletable(absent -> absent ? pinDao.upsert(state.pin) : Completable.complete()));
            }
            if (!state.wasLast) return restore;
            return restore.andThen(conversationDao.byId(conversationId).map(Optional::of)
                    .defaultIfEmpty(Optional.empty()).flatMapCompletable(conversation -> {
                        if (conversation.isPresent()
                                && Objects.equals(conversation.get().getLastMessageSeq(), state.before.getSeq())
                                && conversation.get().getLastMessageDeleted()) {
                            return conversationDao.updateLastMessageContent(conversationId,
                                    state.before.getSeq(), state.before.getContent() != null
                                            ? state.before.getContent() : state.before.getType());
                        }
                        return Completable.complete();
                    }));
        });
    }

    public Single<Result<MessageSearchResult>> searchMessages(String conversationId, String query) {
        return searchMessages(conversationId, query, null, MessageApi.DEFAULT_SEARCH_LIMIT);
    }

    /**
     * Searches one conversation on the server and writes results through Room
     * so a selected hit is available to {@link #loadUntilLoaded} immediately.
     */
    public Single<Result<MessageSearchResult>> searchMessages(String conversationId, String query,
            @Nullable Long beforeSeq) {
        return searchMessages(conversationId, query, beforeSeq, MessageApi.DEFAULT_SEARCH_LIMIT);
    }

    public Single<Result<MessageSearchResult>> searchMessages(String conversationId, String query,
            @Nullable Long beforeSeq, int limit) {
        String trimmed = query.trim();
        if (trimmed.length() < MIN_SEARCH_CHARS) {
            return Single.just(new Result.Success<>(new MessageSearchResult(Collections.emptyList(), false, null)));
        }
        return call(messageApi.searchMessages(conversationId, trimmed,
                        Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT)), beforeSeq))
                .<Result<MessageSearchResult>>flatMap(result -> {
                    if (!(result instanceof Result.Success<?> success)) return Single.just(Result.cast(result));
                    MessagePageDto page = (MessagePageDto) success.getValue();
                    return storeHistoryItems(conversationId, page.getItems())
                            .andThen(currentUserId().map(Optional::of).defaultIfEmpty(Optional.empty()))
                            .<Result<MessageSearchResult>>map(myIdOpt -> {
                                List<ChatMessage> items = new ArrayList<>();
                                for (MessageDto dto : page.getItems()) {
                                    MessageEntity entity = toEntity(dto, conversationId);
                                    if (entity == null) continue;
                                    List<ReactionChip> chips = new ArrayList<>();
                                    if (dto.getReactions() != null) {
                                        for (ReactionSummaryDto summary : dto.getReactions()) {
                                            MessageReactionEntity reaction = toReactionEntity(summary,
                                                    entity.getId(), conversationId, myIdOpt.orElse(null));
                                            if (reaction != null) chips.add(toChip(reaction));
                                        }
                                    }
                                    items.add(toChatMessage(entity, myIdOpt.orElse(null), chips));
                                }
                                return new Result.Success<>(new MessageSearchResult(items,
                                        page.getHasMore(), page.getNextBeforeSeq()));
                            });
                }).subscribeOn(Schedulers.io());
    }

    /** True when {@code row} is the inbox preview used by edit and delete. */
    private Single<Boolean> isLastMessage(MessageEntity row) {
        return conversationDao.byId(row.getConversationId()).map(conversation ->
                Objects.equals(conversation.getLastMessageSeq(), row.getSeq())).defaultIfEmpty(false);
    }

    /** Rewrites the inbox snippet after an edit, guarded by the same sequence. */
    private Completable refreshLastMessagePreview(MessageEntity row) {
        return isLastMessage(row).flatMapCompletable(last -> last
                ? conversationDao.updateLastMessageContent(row.getConversationId(), row.getSeq(),
                        row.getContent() != null ? row.getContent() : row.getType())
                : Completable.complete());
    }

    /**
     * Adds a SENDING outbox row immediately; delivery resolves asynchronously.
     * Media type and attachments are persisted on the outbox so retries after
     * restart reconstruct the same attachment ids and pending bubble.
     */
    public Completable send(String conversationId, String content) {
        return send(conversationId, content, null, MessageDto.TYPE_TEXT, Collections.emptyList());
    }

    public Completable send(String conversationId, String content, @Nullable String replyToId) {
        return send(conversationId, content, replyToId, MessageDto.TYPE_TEXT, Collections.emptyList());
    }

    public Completable send(String conversationId, String content, @Nullable String replyToId,
            String type, List<AttachmentDto> attachments) {
        return nextOutboxCreatedAt().flatMapCompletable(createdAt -> {
            String attachmentsJson = "";
            if (!attachments.isEmpty()) {
                try { attachmentsJson = attachmentsAdapter.toJson(attachments); }
                catch (RuntimeException ignored) { attachmentsJson = ""; }
            }
            OutboxEntity entry = new OutboxEntity(UUID.randomUUID().toString(), conversationId, type,
                    content, replyToId, OutboxState.SENDING, createdAt, attachmentsJson);
            return outboxDao.insert(entry).andThen(dispatchOutbox(entry));
        }).subscribeOn(Schedulers.io());
    }

    /** Stable FIFO key despite same-millisecond sends or a wall-clock setback. */
    private Single<Long> nextOutboxCreatedAt() {
        return outboxDao.pendingOrdered().first(Collections.emptyList()).map(rows -> {
            long persistedMax = 0L;
            for (OutboxEntity row : rows) persistedMax = Math.max(persistedMax, row.getCreatedAt());
            long now = System.currentTimeMillis();
            final long prior = lastOutboxCreatedAt.get();
            long next = Math.max(now, Math.max(persistedMax + 1L, prior + 1L));
            while (true) {
                long observed = lastOutboxCreatedAt.get();
                next = Math.max(next, observed + 1L);
                if (lastOutboxCreatedAt.compareAndSet(observed, next)) return next;
            }
        });
    }

    /** Requeues and dispatches a FAILED bubble after the user taps retry. */
    public Completable retry(String clientMessageId) {
        return outboxDao.byId(clientMessageId).map(Optional::of).defaultIfEmpty(Optional.empty())
                .flatMapCompletable(entryOpt -> {
                    if (entryOpt.isEmpty()) return Completable.complete();
                    OutboxEntity old = entryOpt.get();
                    return nextOutboxCreatedAt().flatMapCompletable(createdAt -> {
                        OutboxEntity queued = new OutboxEntity(old.getClientMessageId(), old.getConversationId(),
                                old.getType(), old.getContent(), old.getReplyToId(), OutboxState.SENDING,
                                createdAt, old.getAttachmentsJson());
                        return outboxDao.insert(queued).andThen(flushOutbox());
                    });
                }).subscribeOn(Schedulers.io());
    }

    /** Flushes SENDING rows in queue order, normally after a connection or reconnect. */
    public Completable flushOutbox() {
        return outboxDao.pendingList().flatMapCompletable(entries ->
                Flowable.fromIterable(entries).concatMapCompletable(this::dispatchOutbox))
                .subscribeOn(Schedulers.io());
    }

    private Completable dispatchOutbox(OutboxEntity entry) {
        return Completable.fromAction(session::ensureConnected).andThen(Completable.defer(() -> {
            SendMessageRequest request = new SendMessageRequest(entry.getClientMessageId(), entry.getType(),
                    entry.getContent(), entry.getReplyToId(), attachmentIds(entry));
            if (session.sendMessage(entry.getConversationId(), request)) return Completable.complete();
            if (session.isCurrentlyOpen()) return Completable.complete();
            return call(messageApi.sendMessage(entry.getConversationId(), request)).flatMapCompletable(result -> {
                if (result instanceof Result.Success<?> success) {
                    return acknowledge(entry.getConversationId(), (MessageDto) success.getValue());
                }
                ApiError error = ((Result.Failure<?>) result).getError();
                if (error instanceof ApiError.RateLimited) {
                    scheduleRateLimitRetry();
                    return Completable.complete();
                }
                if (error instanceof ApiError.Network) return Completable.complete();
                return outboxDao.updateState(entry.getClientMessageId(), OutboxState.FAILED);
            });
        }));
    }

    private List<String> attachmentIds(OutboxEntity entry) {
        List<String> ids = new ArrayList<>();
        for (AttachmentDto attachment : attachments(entry.getAttachmentsJson())) {
            if (attachment.getId() != null) ids.add(attachment.getId());
        }
        return ids;
    }

    /** Shared acknowledgement path for the REST fallback and realtime dedup. */
    private Completable acknowledge(String conversationId, MessageDto dto) {
        MessageEntity entity = toEntity(dto, conversationId);
        if (entity == null) return Completable.complete();
        Completable ack = entity.getClientMessageId() == null ? Completable.complete()
                : outboxDao.delete(entity.getClientMessageId());
        return ack.andThen(messageDao.upsert(entity)).andThen(updateInboxProjection(entity));
    }

    /** POSTs the read watermark and clears local unread state without regression. */
    public Completable sendRead(String conversationId, long upToSeq) {
        if (upToSeq <= 0L) return Completable.complete();
        AtomicReference<Boolean> shouldSend = new AtomicReference<>(false);
        lastMarkedRead.compute(conversationId, (key, last) -> {
            long previous = last == null ? 0L : last;
            if (upToSeq > previous) {
                shouldSend.set(true);
                return upToSeq;
            }
            return previous;
        });
        if (!shouldSend.get()) return Completable.complete();
        return call(messageApi.markRead(conversationId, new ReadRequest(upToSeq)))
                .flatMapCompletable(result -> result instanceof Result.Success<?>
                        ? conversationDao.clearUnread(conversationId) : Completable.complete())
                .subscribeOn(Schedulers.io());
    }

    private Completable routeSessionEvent(ChatEvent event) {
        if (event instanceof ChatEvent.MessageCreated created) return onMessageCreated(created);
        if (event instanceof ChatEvent.MessageUpdated updated) return onMessageUpdated(updated);
        if (event instanceof ChatEvent.MessageDeleted deleted) return onMessageDeleted(deleted);
        if (event instanceof ChatEvent.UserTyping typing) {
            if (typing.getConversationId() != null) {
                typingEvents.onNext(new TypingEvent(typing.getConversationId(), typing.getUserId(), typing.getDisplayName()));
            }
            return Completable.complete();
        }
        if (event instanceof ChatEvent.MessageRead read) {
            String conversationId = read.getConversationId();
            if (conversationId == null) return Completable.complete();
            readEvents.onNext(new ReadEvent(conversationId, read.getUserId(), read.getUpToSeq()));
            Long seq = read.getUpToSeq();
            if (seq == null || read.getUserId() == null) return Completable.complete();
            return currentUserId().map(myId -> !myId.equals(read.getUserId())).defaultIfEmpty(false)
                    .flatMapCompletable(isPeer -> isPeer
                            ? conversationDao.bumpPeerReadSeq(conversationId, seq) : Completable.complete());
        }
        if (event instanceof ChatEvent.SendRejected rejected) {
            String clientId = rejected.getClientMessageId();
            if (clientId == null) return Completable.complete();
            if (ERROR_RATE_LIMITED.equals(rejected.getCode())) {
                scheduleRateLimitRetry();
                return Completable.complete();
            }
            return outboxDao.updateState(clientId, OutboxState.FAILED);
        }
        if (event instanceof ChatEvent.ReactionUpdated reaction) return onReactionUpdated(reaction);
        if (event instanceof ChatEvent.PinUpdated pin) return onPinUpdated(pin);
        return Completable.complete();
    }

    private Completable onMessageCreated(ChatEvent.MessageCreated event) {
        MessageDto dto = event.getMessage();
        String conversationId = firstNonNull(dto.getConversationId(), event.getConversationId());
        if (conversationId == null) return Completable.complete();
        MessageEntity entity = toEntity(dto, conversationId);
        if (entity == null) return Completable.complete();
        Completable acknowledge = Completable.complete();
        if (entity.getClientMessageId() != null) {
            acknowledge = isMine(entity.getSenderId()).flatMapCompletable(mine -> mine
                    ? outboxDao.delete(entity.getClientMessageId()) : Completable.complete());
        }
        return acknowledge.andThen(messageDao.upsert(entity)).andThen(updateInboxProjection(entity));
    }

    private Completable updateInboxProjection(MessageEntity entity) {
        return isMine(entity.getSenderId()).flatMapCompletable(mine -> {
            boolean openHere = Objects.equals(activeConversation.get(), entity.getConversationId());
            Completable update = conversationDao.updateLastMessage(entity.getConversationId(), entity.getSeq(),
                    entity.getContent() != null ? entity.getContent() : entity.getType(),
                    entity.getSenderDisplayName(), entity.getCreatedAt());
            if (!mine && !openHere) update = update.andThen(conversationDao.incrementUnread(entity.getConversationId(), 1));
            if (!mine && openHere) update = update.andThen(sendRead(entity.getConversationId(), entity.getSeq()));
            return update;
        });
    }

    /**
     * Applies the authoritative MESSAGE_UPDATED content and timestamp. Late or
     * duplicate broadcasts are idempotent; tombstoned rows never resurrect.
     */
    private Completable onMessageUpdated(ChatEvent.MessageUpdated event) {
        MessageDto dto = event.getMessage();
        String conversationId = firstNonNull(dto.getConversationId(), event.getConversationId());
        if (conversationId == null) return Completable.complete();
        MessageEntity server = toEntity(dto, conversationId);
        if (server == null) return Completable.complete();
        return messageDao.byId(server.getId()).map(Optional::of).defaultIfEmpty(Optional.empty())
                .flatMapCompletable(rowOpt -> {
                    if (rowOpt.isEmpty()) return messageDao.upsert(server);
                    MessageEntity row = rowOpt.get();
                    if (row.getDeletedAtEpochMs() != null) return Completable.complete();
                    long editedAt = server.getEditedAtEpochMs() != null
                            ? server.getEditedAtEpochMs() : System.currentTimeMillis();
                    MessageEntity updated = copyMessage(row, server.getContent(), row.getAttachmentsJson(),
                            editedAt, row.getDeletedAtEpochMs(), row.getReplyToSnippet());
                    Completable write = sameMessage(updated, row) ? Completable.complete() : messageDao.update(updated);
                    return write.andThen(refreshLastMessagePreview(updated));
                });
    }

    /**
     * Applies MESSAGE_DELETED as a tombstone, removing reaction and pin rows.
     * Duplicate broadcasts retain the first deletion timestamp.
     */
    private Completable onMessageDeleted(ChatEvent.MessageDeleted event) {
        String messageId = event.getMessageId();
        String conversationId = event.getConversationId();
        if (messageId == null || conversationId == null) return Completable.complete();
        if (pendingDeleteIds.contains(messageId)) confirmedDeleteIds.add(messageId);
        long deletedAt = Optional.ofNullable(TimeAndJwt.isoToEpochMsOrNull(event.getDeletedAt()))
                .orElse(System.currentTimeMillis());
        Completable update = messageDao.byId(messageId).flatMapCompletable(row -> {
            if (!conversationId.equals(row.getConversationId())) return Completable.complete();
            Completable write = row.getDeletedAtEpochMs() == null
                    ? messageDao.update(copyMessage(row, "", null, row.getEditedAtEpochMs(), deletedAt,
                            row.getReplyToSnippet()))
                    : Completable.complete();
            return write.andThen(isLastMessage(row).flatMapCompletable(last -> last
                    ? conversationDao.markLastMessageDeleted(conversationId) : Completable.complete()));
        });
        return update.andThen(reactionDao.deleteForMessage(messageId))
                .andThen(pinDao.delete(conversationId, messageId))
                .andThen(messageDao.clearReplySnippets(messageId));
    }

    private void scheduleRateLimitRetry() {
        synchronized (rateLimitRetryLock) {
            if (rateLimitRetry != null && !rateLimitRetry.isDisposed()) return;
            rateLimitRetry = timerScheduler.scheduleDirect(() -> {
                synchronized (rateLimitRetryLock) { rateLimitRetry = null; }
                Disposable disposable = flushOutbox().subscribe(() -> {}, RxJavaPlugins::onError);
                subscriptions.add(disposable);
            }, RATE_LIMIT_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
            subscriptions.add(rateLimitRetry);
        }
    }

    private Completable resyncOnReconnect() {
        return flushOutbox().andThen(Maybe.fromCallable(activeConversation::get)
                .flatMapCompletable(conversationId -> resyncForward(conversationId)
                        .andThen(messageDao.maxSeq(conversationId)
                                .flatMapCompletable(seq -> sendRead(conversationId, seq)))
                        .andThen(refreshPins(conversationId).ignoreElement())));
    }

    /**
     * Replaces the stored reaction summary. Events can arrive before their
     * message row because reactions are keyed by message id and joined at render.
     */
    private Completable onReactionUpdated(ChatEvent.ReactionUpdated event) {
        if (event.getMessageId() == null || event.getConversationId() == null) return Completable.complete();
        String messageId = event.getMessageId();
        String conversationId = event.getConversationId();
        return currentUserId().map(Optional::of).defaultIfEmpty(Optional.empty()).flatMapCompletable(myIdOpt -> {
            List<MessageReactionEntity> rows = new ArrayList<>();
            for (ReactionSummaryDto summary : event.getReactions()) {
                MessageReactionEntity row = toReactionEntity(summary, messageId, conversationId, myIdOpt.orElse(null));
                if (row != null) rows.add(row);
            }
            return reactionDao.replaceForMessage(messageId, rows);
        });
    }

    /** Upserts a pin on {@code pinned=true} and removes it on {@code false}. */
    private Completable onPinUpdated(ChatEvent.PinUpdated event) {
        if (event.getMessageId() == null || event.getConversationId() == null || event.getPinned() == null) {
            return Completable.complete();
        }
        if (event.getPinned()) {
            return pinDao.upsert(new PinnedMessageEntity(event.getConversationId(), event.getMessageId(),
                    System.currentTimeMillis()));
        }
        return pinDao.delete(event.getConversationId(), event.getMessageId());
    }

    /** Optimistic whole-set reaction toggle; newer server state wins over a stale rollback. */
    /**
     * Optimistically flips an emoji chip in Room, then calls the REST toggle.
     * Server response or realtime broadcast reconciles the summary; failure
     * restores the previous snapshot and emits an interaction error.
     */
    public Completable toggleReaction(String messageId, String conversationId, String emoji) {
        return reactionDao.forMessage(messageId).flatMapCompletable(before -> {
            List<MessageReactionEntity> optimistic = toggledCopy(before, messageId, conversationId, emoji);
            return reactionDao.replaceForMessage(messageId, optimistic)
                    .andThen(call(messageApi.toggleReaction(messageId, new ReactionRequest(emoji))))
                    .flatMapCompletable(result -> {
                        if (result instanceof Result.Success<?> success) {
                            ReactionUpdateDtoBox payload = optionalReactionPayload(success.getValue());
                            if (payload.value == null || payload.value.isEmpty()) return Completable.complete();
                            return currentUserId().map(Optional::of).defaultIfEmpty(Optional.empty())
                                    .flatMapCompletable(myIdOpt -> {
                                        List<MessageReactionEntity> rows = new ArrayList<>();
                                        for (ReactionSummaryDto summary : payload.value) {
                                            MessageReactionEntity row = toReactionEntity(summary, messageId,
                                                    conversationId, myIdOpt.orElse(null));
                                            if (row != null) rows.add(row);
                                        }
                                        return reactionDao.replaceForMessage(messageId, rows);
                                    });
                        }
                        ApiError error = ((Result.Failure<?>) result).getError();
                        return reactionDao.forMessage(messageId).flatMapCompletable(current -> {
                            if (current.equals(optimistic)) return reactionDao.replaceForMessage(messageId, before);
                            return Completable.complete();
                        }).andThen(Completable.fromAction(() -> interactionEvents.onNext(error)));
                    });
        }).subscribeOn(Schedulers.io());
    }

    private ReactionUpdateDtoBox optionalReactionPayload(Object body) {
        try {
            if (body == null) return new ReactionUpdateDtoBox(null);
            return new ReactionUpdateDtoBox(((com.nexachat.core.network.dto.ReactionUpdateDto) body).getReactions());
        } catch (ClassCastException ignored) {
            return new ReactionUpdateDtoBox(null);
        }
    }

    /** Returns an optimistic copy of {@code before} with the current user's emoji flipped. */
    private List<MessageReactionEntity> toggledCopy(List<MessageReactionEntity> before,
            String messageId, String conversationId, String emoji) {
        MessageReactionEntity existing = null;
        for (MessageReactionEntity row : before) if (emoji.equals(row.getEmoji())) { existing = row; break; }
        List<MessageReactionEntity> result = new ArrayList<>();
        if (existing != null && existing.getReactedByMe()) {
            int remaining = existing.getCount() - 1;
            for (MessageReactionEntity row : before) {
                if (!emoji.equals(row.getEmoji())) result.add(row);
                else if (remaining > 0) result.add(new MessageReactionEntity(messageId, conversationId,
                        emoji, remaining, false));
            }
        } else {
            for (MessageReactionEntity row : before) {
                if (emoji.equals(row.getEmoji())) continue;
                result.add(row);
            }
            result.add(new MessageReactionEntity(messageId, conversationId, emoji,
                    existing == null ? 1 : existing.getCount() + 1, true));
        }
        return result;
    }

    /**
     * Refreshes pins from GET /pins. Tolerates documented response spellings
     * and writes the complete set through Room.
     */
    public Single<Result<Unit>> refreshPins(String conversationId) {
        return call(messageApi.getPins(conversationId)).<Result<Unit>>flatMap(result -> {
            if (!(result instanceof Result.Success<?> success)) return Single.just(Result.cast(result));
            try {
                String body = ((ResponseBody) success.getValue()).string();
                PinsBody parsed = parsePinsBody(conversationId, body);
                Completable embedded = parsed.embedded.isEmpty() ? Completable.complete()
                        : messageDao.upsertAll(mapEntities(parsed.embedded, conversationId));
                return embedded.andThen(pinDao.replaceForConversation(conversationId, parsed.pins))
                        .andThen(Single.just(new Result.Success<>(Unit.INSTANCE)));
            } catch (IOException | RuntimeException error) {
                return Single.just(Result.failure(new ApiError.Network("body read failed")));
            }
        }).subscribeOn(Schedulers.io());
    }

    /** Optimistically pins a message; restores and signals on authorization or limit failure. */
    public Completable pinMessage(String conversationId, String messageId) {
        PinnedMessageEntity entry = new PinnedMessageEntity(conversationId, messageId, System.currentTimeMillis());
        return pinDao.upsert(entry).andThen(call(messageApi.pinMessage(conversationId, new PinRequest(messageId))))
                .flatMapCompletable(result -> {
                    if (result instanceof Result.Success<?>) return Completable.complete();
                    ApiError error = ((Result.Failure<?>) result).getError();
                    return pinDao.find(conversationId, messageId).map(Optional::of)
                            .defaultIfEmpty(Optional.empty()).flatMapCompletable(current ->
                                    current.isPresent() && samePin(current.get(), entry)
                                            ? pinDao.delete(conversationId, messageId) : Completable.complete())
                            .andThen(Completable.fromAction(() -> interactionEvents.onNext(error)));
                }).subscribeOn(Schedulers.io());
    }

    /** Optimistically removes a pin; restores it and signals if the request fails. */
    public Completable unpinMessage(String conversationId, String messageId) {
        return pinDao.find(conversationId, messageId).map(Optional::of).defaultIfEmpty(Optional.empty())
                .flatMapCompletable(before -> pinDao.delete(conversationId, messageId)
                        .andThen(call(messageApi.unpinMessage(conversationId, messageId)))
                        .flatMapCompletable(result -> {
                            if (result instanceof Result.Success<?>) return Completable.complete();
                            ApiError error = ((Result.Failure<?>) result).getError();
                            return pinDao.find(conversationId, messageId).isEmpty()
                                    .flatMapCompletable(absent -> absent && before.isPresent()
                                            ? pinDao.upsert(before.get()) : Completable.complete())
                                    .andThen(Completable.fromAction(() -> interactionEvents.onNext(error)));
                        })).subscribeOn(Schedulers.io());
    }

    /** Forward resync pages until the server reports no further history. */
    public Completable resyncForward(String conversationId) {
        return messageDao.maxSeq(conversationId).flatMapCompletable(afterSeq -> {
            if (afterSeq <= 0L) return refreshHistory(conversationId).ignoreElement();
            return resyncForwardPage(conversationId, afterSeq);
        }).subscribeOn(Schedulers.io());
    }

    private Completable resyncForwardPage(String conversationId, long afterSeq) {
        return call(messageApi.getMessages(conversationId, null, afterSeq, MessageApi.DEFAULT_PAGE_SIZE))
                .flatMapCompletable(result -> {
                    if (!(result instanceof Result.Success<?> success)) return Completable.complete();
                    MessagePageDto page = (MessagePageDto) success.getValue();
                    return storeHistoryItems(conversationId, page.getItems()).andThen(Single.fromCallable(() -> {
                        long lastSeq = afterSeq;
                        for (MessageDto dto : page.getItems()) {
                            MessageEntity entity = toEntity(dto, conversationId);
                            if (entity != null) lastSeq = Math.max(lastSeq, entity.getSeq());
                        }
                        return lastSeq;
                    })).flatMapCompletable(lastSeq -> !page.getHasMore() || lastSeq <= afterSeq
                            ? Completable.complete() : resyncForwardPage(conversationId, lastSeq));
                });
    }

    /** Removes local server-owned data when the session ends. */
    private Completable wipeOnLogout() {
        return messageDao.deleteAll().andThen(conversationDao.clear()).andThen(outboxDao.clear())
                .andThen(reactionDao.clear()).andThen(pinDao.clear())
                .andThen(Completable.fromAction(() -> {
                    lastMarkedRead.clear();
                    activeConversation.set(null);
                    tokenToUser = null;
                }));
    }

    /** Access-token subject, memoized until the token changes. */
    public Maybe<String> currentUserId() {
        return tokenStore.readTokens().flatMap(tokens -> {
            String token = tokens.getAccessToken();
            TokenUser cached = tokenToUser;
            if (cached != null && cached.token.equals(token)) return Maybe.just(cached.userId);
            String userId = TimeAndJwt.userIdFromJwt(token, moshi);
            if (userId == null) return Maybe.empty();
            tokenToUser = new TokenUser(token, userId);
            return Maybe.just(userId);
        }).subscribeOn(Schedulers.io());
    }

    public Single<Boolean> isMine(@Nullable String senderId) {
        if (senderId == null) return Single.just(false);
        return currentUserId().map(senderId::equals).defaultIfEmpty(false);
    }

    /** Builds the render model from a persisted message and its reaction rows. */
    private ChatMessage toChatMessage(MessageEntity row, @Nullable String myId, List<ReactionChip> reactions) {
        return new ChatMessage(row.getId(), row.getConversationId(), row.getSeq(), row.getSenderId(),
                row.getSenderDisplayName(), row.getType(), row.getContent(), row.getReplyToId(),
                row.getReplyToSenderName(), row.getReplyToSnippet(), row.getCreatedAt(),
                row.getClientMessageId(), DeliveryStatus.SENT, Objects.equals(row.getSenderId(), myId),
                attachments(row.getAttachmentsJson()), reactions, row.getEditedAtEpochMs(), row.getDeletedAtEpochMs());
    }

    private ChatMessage toChatMessage(OutboxEntity row) {
        return new ChatMessage(row.getClientMessageId(), row.getConversationId(), null, "", null,
                row.getType(), row.getContent(), row.getReplyToId(), null, null, row.getCreatedAt(),
                row.getClientMessageId(), OutboxState.FAILED.equals(row.getState())
                        ? DeliveryStatus.FAILED : DeliveryStatus.SENDING,
                true, attachments(row.getAttachmentsJson()), Collections.emptyList(), null, null);
    }

    private ChatMessage pinStub(PinnedMessageEntity pin) {
        return new ChatMessage(pin.getMessageId(), pin.getConversationId(), null, "", null,
                MessageDto.TYPE_TEXT, null, null, null, null, pin.getPinnedAt(), null,
                DeliveryStatus.SENT, false, Collections.emptyList(), Collections.emptyList(), null, null);
    }

    @Nullable
    private MessageEntity toEntity(MessageDto dto, @Nullable String fallbackConversationId) {
        String conversationId = firstNonNull(dto.getConversationId(), fallbackConversationId);
        if (conversationId == null) return null;
        boolean deleted = dto.getDeletedAt() != null && !dto.getDeletedAt().isBlank();
        String attachmentsJson = null;
        if (!deleted && !dto.getAttachments().isEmpty()) {
            try { attachmentsJson = attachmentsAdapter.toJson(dto.getAttachments()); }
            catch (RuntimeException ignored) { attachmentsJson = null; }
        }
        return new MessageEntity(dto.getId(), conversationId, dto.getSeq() == null ? 0L : dto.getSeq(),
                Optional.ofNullable(dto.getResolvedSenderId()).orElse(""), dto.getResolvedSenderName(),
                dto.getResolvedType(), deleted ? "" : dto.getContent(), dto.getClientMessageId(),
                dto.getResolvedReplyToId(), dto.getReplyTo() == null ? null : dto.getReplyTo().getResolvedSenderName(),
                dto.getReplyTo() == null ? null : dto.getReplyTo().getResolvedSnippet(),
                TimeAndJwt.isoToEpochMs(dto.getCreatedAt()), attachmentsJson,
                TimeAndJwt.isoToEpochMsOrNull(dto.getEditedAt()), TimeAndJwt.isoToEpochMsOrNull(dto.getDeletedAt()));
    }

    /** Parses attachmentsJson; missing, blank, or malformed data becomes an empty list. */
    private List<AttachmentDto> attachments(@Nullable String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<AttachmentDto> parsed = attachmentsAdapter.fromJson(json);
            return parsed == null ? Collections.emptyList() : parsed;
        } catch (IOException | RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    @Nullable
    /**
     * Converts a REST or realtime summary to a Room row. Explicit reactedByMe
     * wins; otherwise userIds is used when the current user id is known.
     */
    private MessageReactionEntity toReactionEntity(ReactionSummaryDto summary, String messageId,
            String conversationId, @Nullable String myId) {
        String emoji = summary.getEmoji();
        if (emoji == null || emoji.isBlank()) return null;
        Integer count = summary.getCount();
        Boolean reactedByMe = summary.getReactedByMe();
        if (reactedByMe == null) reactedByMe = summary.getByMe();
        if (reactedByMe == null) reactedByMe = myId != null && summary.getUserIds().contains(myId);
        return new MessageReactionEntity(messageId, conversationId, emoji,
                Math.max(0, count == null ? summary.getUserIds().size() : count), reactedByMe);
    }

    private ReactionChip toChip(MessageReactionEntity row) {
        return new ReactionChip(row.getEmoji(), row.getCount(), row.getReactedByMe());
    }

    /**
     * Parses GET /pins variants into rows, preserving wire order through
     * descending pinnedAt timestamps for deterministic banner ordering.
     */
    private PinsBody parsePinsBody(String conversationId, String body) throws IOException {
        Object parsed = anyAdapter.fromJson(body);
        List<?> entries;
        if (parsed instanceof List<?> list) {
            entries = list;
        } else if (parsed instanceof Map<?, ?> map) {
            List<?> found = null;
            for (String key : PIN_LIST_KEYS) if (map.get(key) instanceof List<?> list) { found = list; break; }
            if (found == null) {
                for (Object value : map.values()) if (value instanceof List<?> list) { found = list; break; }
            }
            if (found == null) return new PinsBody(Collections.emptyList(), Collections.emptyList());
            entries = found;
        } else {
            return new PinsBody(Collections.emptyList(), Collections.emptyList());
        }
        long now = System.currentTimeMillis();
        List<PinnedMessageEntity> pins = new ArrayList<>();
        List<MessageDto> embedded = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            String id = pinMessageIdOf(entry);
            if (id != null) pins.add(new PinnedMessageEntity(conversationId, id, now - i));
            if (entry instanceof Map<?, ?> map && map.containsKey("message")) {
                try {
                    MessageDto dto = messageAdapter.fromJsonValue(map.get("message"));
                    if (dto != null) embedded.add(dto);
                } catch (RuntimeException ignored) {
                    // Ignore malformed embedded rows, while retaining valid pin ids.
                }
            }
        }
        return new PinsBody(pins, embedded);
    }

    @Nullable
    /** Extracts a pin id from a bare string or an object containing the id. */
    private String pinMessageIdOf(Object entry) {
        if (entry instanceof String value) return value.isBlank() ? null : value;
        if (!(entry instanceof Map<?, ?> map)) return null;
        Object value = map.get("messageId");
        if (!(value instanceof String)) value = map.get("id");
        if (value instanceof String id) return id;
        Object message = map.get("message");
        if (message instanceof Map<?, ?> nested) {
            value = nested.get("id");
            if (!(value instanceof String)) value = nested.get("messageId");
            if (value instanceof String id) return id;
        }
        return null;
    }

    /**
     * Adapts the shared network result into the repository Result contract.
     * Retrofit and mapping work remain cancellable through the returned Single.
     */
    /**
     * Adapts safeApiCall into the repository Result contract. Empty successful
     * bodies can surface as an erased cast later, so they become a typed
     * unexpected-empty-body failure instead of escaping the stream.
     */
    private <T> Single<Result<T>> call(Call<T> call) {
        return ApiResultKt.safeApiCall(call).<Result<T>>map(result -> {
            if (result instanceof ApiResult.Success<?> success) {
                @SuppressWarnings("unchecked") T body = (T) success.getBody();
                return new Result.Success<>(body);
            }
            if (result instanceof ApiResult.HttpError<?> http) {
                return Result.failure(ApiError.from(http.getStatus(), http.getErrorCode()));
            }
            ApiResult.NetworkError<?> network = (ApiResult.NetworkError<?>) result;
            return Result.failure(new ApiError.Network(network.getCause().getMessage()));
        }).onErrorReturn(error -> {
            Exceptions.throwIfFatal(error);
            return Result.failure(error instanceof ClassCastException
                    ? new ApiError.Unknown("UNEXPECTED_EMPTY_BODY")
                    : new ApiError.Network(error.getMessage()));
        }).subscribeOn(Schedulers.io());
    }

    private void collectInBackground(Completable stream) {
        Disposable disposable = stream.subscribeOn(Schedulers.io()).subscribe(() -> {}, RxJavaPlugins::onError);
        subscriptions.add(disposable);
    }

    private void scheduleDetached(Completable cleanup) {
        Disposable disposable = cleanup.subscribeOn(Schedulers.io()).subscribe(() -> {}, RxJavaPlugins::onError);
        subscriptions.add(disposable);
    }

    /** Stops repository-owned realtime subscriptions and any delayed retry. */
    public void dispose() {
        subscriptions.dispose();
        Disposable retry = rateLimitRetry;
        if (retry != null) retry.dispose();
    }

    private static String firstNonNull(@Nullable String first, @Nullable String second) {
        return first != null ? first : second;
    }

    private static boolean samePin(PinnedMessageEntity left, PinnedMessageEntity right) {
        return left.getConversationId().equals(right.getConversationId())
                && left.getMessageId().equals(right.getMessageId()) && left.getPinnedAt() == right.getPinnedAt();
    }

    private static boolean sameMessage(MessageEntity left, MessageEntity right) {
        return Objects.equals(left.getId(), right.getId())
                && Objects.equals(left.getConversationId(), right.getConversationId())
                && left.getSeq() == right.getSeq() && Objects.equals(left.getSenderId(), right.getSenderId())
                && Objects.equals(left.getSenderDisplayName(), right.getSenderDisplayName())
                && Objects.equals(left.getType(), right.getType()) && Objects.equals(left.getContent(), right.getContent())
                && Objects.equals(left.getClientMessageId(), right.getClientMessageId())
                && Objects.equals(left.getReplyToId(), right.getReplyToId())
                && Objects.equals(left.getReplyToSenderName(), right.getReplyToSenderName())
                && Objects.equals(left.getReplyToSnippet(), right.getReplyToSnippet())
                && left.getCreatedAt() == right.getCreatedAt()
                && Objects.equals(left.getAttachmentsJson(), right.getAttachmentsJson())
                && Objects.equals(left.getEditedAtEpochMs(), right.getEditedAtEpochMs())
                && Objects.equals(left.getDeletedAtEpochMs(), right.getDeletedAtEpochMs());
    }

    private static MessageEntity copyMessage(MessageEntity source, @Nullable String content,
            @Nullable String attachmentsJson, @Nullable Long editedAtEpochMs,
            @Nullable Long deletedAtEpochMs, @Nullable String replyToSnippet) {
        return new MessageEntity(source.getId(), source.getConversationId(), source.getSeq(),
                source.getSenderId(), source.getSenderDisplayName(), source.getType(), content,
                source.getClientMessageId(), source.getReplyToId(), source.getReplyToSenderName(),
                replyToSnippet, source.getCreatedAt(), attachmentsJson, editedAtEpochMs, deletedAtEpochMs);
    }

    private List<MessageEntity> mapEntities(List<MessageDto> dtos, String conversationId) {
        List<MessageEntity> entities = new ArrayList<>(dtos.size());
        for (MessageDto dto : dtos) {
            MessageEntity entity = toEntity(dto, conversationId);
            if (entity != null) entities.add(entity);
        }
        return entities;
    }

    private static final class TokenUser {
        final String token;
        final String userId;
        TokenUser(String token, String userId) { this.token = token; this.userId = userId; }
    }
    private static final class DeleteRollback {
        final MessageEntity before;
        final MessageEntity optimistic;
        final List<MessageReactionEntity> reactions;
        @Nullable final PinnedMessageEntity pin;
        final boolean wasLast;
        DeleteRollback(MessageEntity before, MessageEntity optimistic, List<MessageReactionEntity> reactions,
                @Nullable PinnedMessageEntity pin, boolean wasLast) {
            this.before = before; this.optimistic = optimistic; this.reactions = reactions;
            this.pin = pin; this.wasLast = wasLast;
        }
    }
    private static final class HistoryReactions {
        final MessageDto dto;
        final MessageEntity row;
        HistoryReactions(MessageDto dto, MessageEntity row) { this.dto = dto; this.row = row; }
    }
    private static final class MessageSnapshot {
        final List<MessageEntity> messages;
        final List<OutboxEntity> pending;
        final List<MessageReactionEntity> reactions;
        MessageSnapshot(List<MessageEntity> messages, List<OutboxEntity> pending,
                List<MessageReactionEntity> reactions) {
            this.messages = messages; this.pending = pending; this.reactions = reactions;
        }
    }
    private static final class PinSnapshot {
        final List<PinnedMessageEntity> pins;
        final List<MessageEntity> messages;
        PinSnapshot(List<PinnedMessageEntity> pins, List<MessageEntity> messages) {
            this.pins = pins; this.messages = messages;
        }
    }
    private static final class PinsBody {
        final List<PinnedMessageEntity> pins;
        final List<MessageDto> embedded;
        PinsBody(List<PinnedMessageEntity> pins, List<MessageDto> embedded) {
            this.pins = pins; this.embedded = embedded;
        }
    }
    private static final class ReactionUpdateDtoBox {
        @Nullable final List<ReactionSummaryDto> value;
        ReactionUpdateDtoBox(@Nullable List<ReactionSummaryDto> value) { this.value = value; }
    }
}
