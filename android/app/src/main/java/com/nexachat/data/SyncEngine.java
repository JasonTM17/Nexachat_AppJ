package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import com.nexachat.core.database.ConversationWatermark;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.database.MessageReactionEntity;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.ReactionSummaryDto;
import com.nexachat.core.network.dto.ReactionUpdateDto;
import com.nexachat.core.network.dto.SyncRequest;
import com.nexachat.core.network.dto.SyncResponse;
import com.nexachat.core.storage.TokenStore;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Client Delta Sync Engine: aggregates all conversation watermarks,
 * requests POST /api/v1/sync, and applies the delta bundle in a single Room transaction.
 */
@Singleton
public final class SyncEngine {
    private static final int MAX_WATERMARKS_PER_REQUEST = 100;
    private static final int DELTA_LIMIT_PER_CONVERSATION = 50;
    /** Upper bound on continuation requests per watermark batch; prevents a stuck server page. */
    static final int MAX_DRAIN_PAGES = 20;
    /** Maximum consecutive bounded retries when deltas continue past the page bound. */
    static final int MAX_BOUNDED_RETRIES = 2;

    private final AppDatabase appDatabase;
    private final MessageApi messageApi;
    private final MessageDao messageDao;
    private final ConversationDao conversationDao;
    private final ReactionDao reactionDao;
    private final TokenStore tokenStore;
    private final Moshi moshi;
    private final TokenRefresher tokenRefresher;
    private final SessionOperationTracker operationTracker;
    private final JsonAdapter<List<AttachmentDto>> attachmentsAdapter;
    private final AtomicBoolean hasPendingContinuation = new AtomicBoolean();
    /** Server time of the last successful sync, echoed back as {@code since} so edits/deletes/reactions delta-sync. */
    @Nullable
    private volatile String lastServerTime;
    private final Object flightLock = new Object();
    @Nullable private Completable inFlight;
    private long flightEpoch = Long.MIN_VALUE;
    private final SyncPageStore pageStore;
    @VisibleForTesting Runnable onFlightJoined = () -> {};

    @Inject
    public SyncEngine(
            AppDatabase appDatabase,
            MessageApi messageApi,
            MessageDao messageDao,
            ConversationDao conversationDao,
            ReactionDao reactionDao,
            TokenStore tokenStore,
            Moshi moshi,
            TokenRefresher tokenRefresher,
            SessionOperationTracker operationTracker) {
        this.appDatabase = appDatabase;
        this.messageApi = messageApi;
        this.messageDao = messageDao;
        this.conversationDao = conversationDao;
        this.reactionDao = reactionDao;
        this.tokenStore = tokenStore;
        this.moshi = moshi;
        this.tokenRefresher = tokenRefresher;
        this.operationTracker = operationTracker;
        this.attachmentsAdapter = moshi.adapter(Types.newParameterizedType(List.class, AttachmentDto.class));
        this.pageStore = new SyncPageStore(appDatabase, tokenRefresher, tokenStore, moshi);
    }

    /** Compatibility constructor for existing package-local sync tests. */
    @VisibleForTesting
    SyncEngine(
            AppDatabase appDatabase,
            MessageApi messageApi,
            MessageDao messageDao,
            ConversationDao conversationDao,
            ReactionDao reactionDao,
            TokenStore tokenStore,
            Moshi moshi) {
        this(appDatabase, messageApi, messageDao, conversationDao, reactionDao, tokenStore, moshi,
                testTokenRefresher(tokenStore), new SessionOperationTracker());
    }

    private static TokenRefresher testTokenRefresher(TokenStore tokenStore) {
        TokenRefresher refresher = new TokenRefresher(
                tokenStore, new SessionEvents(), () -> null);
        refresher.onSessionEstablished(1L);
        return refresher;
    }

    /** Whether the sync stopped at the hard page bound with deltas still pending on the server. */
    public boolean hasPendingContinuation() {
        return hasPendingContinuation.get();
    }

    /**
     * Gathers all conversation watermarks from Room, requests delta bundle from server,
     * and atomically ingests new, edited, deleted messages and reaction updates in a Room transaction.
     * Continues past the hard 20-page bound via bounded retries without stalling cursors or looping indefinitely.
     */
    public Completable sync() {
        return Completable.defer(() -> {
            long expectedEpoch = tokenRefresher.sessionEpoch();
            if (!tokenRefresher.isSessionCurrent(expectedEpoch)) {
                return Completable.complete();
            }
            synchronized (flightLock) {
                if (inFlight != null && flightEpoch == expectedEpoch) {
                    onFlightJoined.run();
                    return inFlight;
                }
                flightEpoch = expectedEpoch;
                AtomicReference<Completable> identity = new AtomicReference<>();
                Completable shared = operationTracker.track(Completable.defer(() -> syncAttempt(expectedEpoch))
                    .subscribeOn(Schedulers.io()))
                    .onErrorResumeNext(error -> error instanceof CancellationException
                            ? Completable.complete()
                            : Completable.error(error))
                    .doOnTerminate(() -> {
                        synchronized (flightLock) {
                            if (inFlight == identity.get()) inFlight = null;
                        }
                    }).cache();
                identity.set(shared);
                inFlight = shared;
                return shared;
            }
        });
    }

    private Completable syncAttempt(long expectedEpoch) {
        if (appDatabase.syncCursorDao() == null) return legacySyncAttempt(0, expectedEpoch);
        return loadCursorState(expectedEpoch).flatMapCompletable(cursor -> {
            // A real Room14 database always supplies this DAO. Old package-local fixtures
            // without it continue to exercise the unchanged legacy lane.
            if (cursor.userId == null) return Completable.complete();
            return conversationDao.getAllWatermarks().flatMapCompletable(watermarks ->
                    Completable.create(emitter -> {
                        try {
                            boolean pending = new SyncV2Consumer(pageStore, tokenRefresher, this::fetchDeltaResponse)
                                    .sync(cursor.userId, expectedEpoch, watermarks);
                            if (!emitter.isDisposed()) {
                                tokenRefresher.runIfSessionCurrent(expectedEpoch,
                                        () -> hasPendingContinuation.set(pending));
                                emitter.onComplete();
                            }
                        } catch (Throwable failure) {
                            io.reactivex.rxjava3.exceptions.Exceptions.throwIfFatal(failure);
                            emitter.tryOnError(failure);
                        }
                    })
                    .onErrorResumeNext(error -> error instanceof SyncPageStore.LegacyRequired
                            ? Completable.fromAction(() -> pageStore.legacyFallback(
                                    cursor.userId, expectedEpoch, watermarks))
                                    .andThen(legacySyncAttempt(0, expectedEpoch))
                            : Completable.error(error)));
        });
    }

    @VisibleForTesting void setSyncFaultPoints(Runnable beforeCommit, Runnable afterCommit) {
        pageStore.beforeCommit = beforeCommit;
        pageStore.afterCommit = afterCommit;
    }

    /** Lifecycle events share the same revision comparison, Room transaction and P01 write fence. */
    Completable applyServerMessage(MessageDto dto, String conversation) {
        return applyServerMessage(dto, conversation, false);
    }

    Completable applyServerMessage(MessageDto dto, String conversation, boolean countUnread) {
        return applyServerHistoryOrEvent(dto, conversation, countUnread, false);
    }

    Completable applyServerHistoryOrEvent(MessageDto dto, String conversation, boolean countUnread,
            boolean historyReactions) {
        long epoch = tokenRefresher.sessionEpoch();
        return loadCursorState(epoch).flatMapCompletable(cursor -> Completable.fromAction(() -> {
            if (cursor.userId != null && appDatabase.syncCursorDao() != null) {
                pageStore.lifecycle(cursor.userId, epoch, dto, conversation, countUnread, historyReactions);
            }
        }));
    }

    Completable applyServerDelete(String id, String conversation, long version, long generation, Long at) {
        long epoch = tokenRefresher.sessionEpoch();
        return loadCursorState(epoch).flatMapCompletable(cursor -> Completable.fromAction(() -> {
            if (cursor.userId != null && appDatabase.syncCursorDao() != null) {
                pageStore.deleteLifecycle(cursor.userId, epoch, id, conversation, version, generation, at);
            }
        }));
    }

    private Completable legacySyncAttempt(int retryCount, long expectedEpoch) {
        if (!tokenRefresher.isSessionCurrent(expectedEpoch)) {
            return Completable.error(new CancellationException("Sync session changed"));
        }
        return loadCursorState(expectedEpoch).flatMapCompletable(cursor ->
                Single.zip(
                                messageDao.getAllMaxSeqs(),
                                conversationDao.getAllWatermarks(),
                                this::mergeWatermarks)
                        .flatMapCompletable(watermarks -> {
                            if (!tokenRefresher.isSessionCurrent(expectedEpoch)) {
                                return Completable.error(
                                        new CancellationException("Sync session changed"));
                            }
                            lastServerTime = cursor.serverTime;
                            if (watermarks.isEmpty()) {
                                hasPendingContinuation.set(false);
                                return Completable.complete();
                            }
                            List<SyncRequest.Watermark> reqWatermarks =
                                    new ArrayList<>(watermarks.size());
                            for (ConversationWatermark wm : watermarks) {
                                reqWatermarks.add(new SyncRequest.Watermark(
                                        wm.getConversationId(), wm.getMaxSeq()));
                            }
                            List<SyncRequest> requests = splitWatermarks(
                                    reqWatermarks, cursor.serverTime);
                            AtomicReference<String> earliestServerTime = new AtomicReference<>();
                            AtomicBoolean missingServerTime = new AtomicBoolean();
                            AtomicBoolean batchHasMore = new AtomicBoolean();
                            return Flowable.fromIterable(requests)
                                    .concatMapCompletable(request -> drainDeltaRequest(
                                            request, earliestServerTime, missingServerTime,
                                            batchHasMore, cursor.userId, expectedEpoch))
                                    .andThen(Completable.defer(() -> {
                                        String serverTime = earliestServerTime.get();
                                        Completable persist = (missingServerTime.get() || serverTime == null)
                                                ? Completable.complete()
                                                : persistServerTime(
                                                        cursor, serverTime, expectedEpoch);
                                        return persist.andThen(Completable.defer(() -> {
                                            if (batchHasMore.get()) {
                                                hasPendingContinuation.set(true);
                                                if (retryCount < MAX_BOUNDED_RETRIES) {
                                                    return legacySyncAttempt(retryCount + 1, expectedEpoch);
                                                }
                                            } else {
                                                hasPendingContinuation.set(false);
                                            }
                                            return Completable.complete();
                                        }));
                                    }));
                        }));
    }

    /**
     * Applies one watermark batch and follows every {@code hasMore} page up to
     * {@link #MAX_DRAIN_PAGES}. The caller persists what was drained per watermark
     * and advances the server-time cursor so work is never stalled.
     */
    private Completable drainDeltaRequest(
            SyncRequest request,
            AtomicReference<String> earliestServerTime,
            AtomicBoolean missingServerTime,
            AtomicBoolean batchHasMore,
            @Nullable String ownerUserId,
            long expectedEpoch) {
        return drainDeltaPage(
                request.getSince(),
                new ArrayList<>(request.getWatermarks()),
                0,
                earliestServerTime,
                missingServerTime,
                batchHasMore,
                ownerUserId,
                expectedEpoch);
    }

    private Completable drainDeltaPage(
            @Nullable String since,
            List<SyncRequest.Watermark> watermarks,
            int page,
            AtomicReference<String> earliestServerTime,
            AtomicBoolean missingServerTime,
            AtomicBoolean batchHasMore,
            @Nullable String ownerUserId,
            long expectedEpoch) {
        if (!tokenRefresher.isSessionCurrent(expectedEpoch)) {
            return Completable.error(new CancellationException("Sync session changed"));
        }
        if (watermarks.isEmpty()) {
            return Completable.complete();
        }
        if (page >= MAX_DRAIN_PAGES) {
            batchHasMore.set(true);
            return Completable.complete();
        }
        SyncRequest request = new SyncRequest(
                watermarks, DELTA_LIMIT_PER_CONVERSATION, since);
        return fetchDeltaResponse(request).flatMapCompletable(response -> {
            applyDeltaBundle(response, watermarks, ownerUserId, expectedEpoch);
            if (response.getServerTime() == null) {
                missingServerTime.set(true);
            } else {
                keepEarliestServerTime(earliestServerTime, response.getServerTime());
            }
            DeltaContinuation continuation = nextDeltaWatermarks(watermarks, response);
            if (continuation.stalled()) {
                if (!canContinueByTimestamp(since, watermarks, response, continuation)) {
                    return Completable.error(new IOException("delta sync page made no progress"));
                }
                // Legacy edit and recall pages have no sequence or reaction
                // continuation key. Save their capped serverTime, then retry
                // the batch from that timestamp after other cursors drain.
                batchHasMore.set(true);
            }
            if (continuation.watermarks().isEmpty()) {
                return Completable.complete();
            }
            if (page + 1 >= MAX_DRAIN_PAGES) {
                batchHasMore.set(true);
                return Completable.complete();
            }
            return drainDeltaPage(since, continuation.watermarks(), page + 1,
                    earliestServerTime, missingServerTime, batchHasMore, ownerUserId, expectedEpoch);
        });
    }

    /** Continuation keys that advanced, plus whether another delta made no progress. */
    private record DeltaContinuation(List<SyncRequest.Watermark> watermarks, boolean stalled) {
    }

    private static DeltaContinuation nextDeltaWatermarks(
            List<SyncRequest.Watermark> requested, SyncResponse response) {
        Map<String, SyncRequest.Watermark> byId = new HashMap<>();
        for (SyncRequest.Watermark watermark : requested) {
            byId.put(watermark.getConversationId(), watermark);
        }
        List<SyncRequest.Watermark> next = new ArrayList<>();
        boolean stuck = false;
        for (SyncResponse.ConversationDeltaDto delta : response.getDeltas()) {
            String conversationId = delta.getConversationId();
            if (conversationId == null) continue;
            SyncRequest.Watermark current = byId.get(conversationId);
            if (current == null || !delta.isHasMore()) continue;

            long nextLastSeq = current.getLastSeq();
            for (MessageDto message : delta.getNewMessages()) {
                if (message.getSeq() != null) {
                    nextLastSeq = Math.max(nextLastSeq, message.getSeq());
                }
            }
            if (delta.getNewMessages().isEmpty()) {
                // No unread rows to advance past; the absolute maximum only
                // moves the watermark forward so a stuck server page is detected.
                nextLastSeq = Math.max(nextLastSeq, delta.getMaxSeq());
            }
            Long nextCursor = delta.getReactionCursor() != null
                    ? delta.getReactionCursor()
                    : current.getReactionCursor();
            if (nextLastSeq == current.getLastSeq()
                    && Objects.equals(nextCursor, current.getReactionCursor())) {
                stuck = true;
                continue;
            }
            next.add(new SyncRequest.Watermark(conversationId, nextLastSeq, nextCursor));
        }
        return new DeltaContinuation(next, stuck);
    }

    private static boolean canContinueByTimestamp(
            @Nullable String since,
            List<SyncRequest.Watermark> requested,
            SyncResponse response,
            DeltaContinuation continuation) {
        if (!continuation.stalled() || since == null || response.getServerTime() == null) {
            return false;
        }
        Instant sinceInstant = parseServerTime(since);
        Instant responseTime = parseServerTime(response.getServerTime());
        if (sinceInstant == null || responseTime == null || !responseTime.isAfter(sinceInstant)) {
            return false;
        }

        Map<String, SyncRequest.Watermark> byId = new HashMap<>();
        for (SyncRequest.Watermark watermark : requested) {
            byId.put(watermark.getConversationId(), watermark);
        }
        boolean hasTimestampOnlyStall = false;
        for (SyncResponse.ConversationDeltaDto delta : response.getDeltas()) {
            if (!delta.isHasMore() || delta.getConversationId() == null) continue;
            SyncRequest.Watermark current = byId.get(delta.getConversationId());
            if (current == null) continue;

            long nextLastSeq = current.getLastSeq();
            for (MessageDto message : delta.getNewMessages()) {
                if (message.getSeq() != null) {
                    nextLastSeq = Math.max(nextLastSeq, message.getSeq());
                }
            }
            if (delta.getNewMessages().isEmpty()) {
                nextLastSeq = Math.max(nextLastSeq, delta.getMaxSeq());
            }
            Long nextCursor = delta.getReactionCursor() != null
                    ? delta.getReactionCursor()
                    : current.getReactionCursor();
            boolean cursorAdvanced = nextLastSeq != current.getLastSeq()
                    || !Objects.equals(nextCursor, current.getReactionCursor());
            if (cursorAdvanced) continue;
            if (!delta.getNewMessages().isEmpty() || !delta.getReactionUpdates().isEmpty()
                    || (delta.getEditedMessages().isEmpty() && delta.getDeletedMessageIds().isEmpty())) {
                return false;
            }
            hasTimestampOnlyStall = true;
        }
        return hasTimestampOnlyStall;
    }

    private static void keepEarliestServerTime(AtomicReference<String> earliest, String candidate) {
        earliest.updateAndGet(current -> {
            if (current == null) return candidate;
            Instant currentInstant = parseServerTime(current);
            Instant candidateInstant = parseServerTime(candidate);
            if (currentInstant == null || candidateInstant == null) {
                // Opaque sync cursors advance on the last successful batch;
                // legacy edit/recall pages use ISO-8601 timestamps and keep the earliest.
                if (currentInstant == null && candidateInstant == null) {
                    return candidate;
                }
                if (currentInstant == null) return candidate;
                return current;
            }
            return candidateInstant.isBefore(currentInstant) ? candidate : current;
        });
    }

    @Nullable
    private static Instant parseServerTime(@Nullable String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * A successful delta response lists every conversation the caller can still
     * access. An omitted watermark therefore means the conversation was deleted
     * or the caller lost membership, so its local cache is removed atomically
     * with the pages already applied.
     */
    private static List<String> missingConversations(
            List<SyncRequest.Watermark> requested, SyncResponse response) {
        java.util.Set<String> returned = new java.util.HashSet<>();
        for (SyncResponse.ConversationDeltaDto delta : response.getDeltas()) {
            if (delta.getConversationId() != null) {
                returned.add(delta.getConversationId());
            }
        }
        List<String> missing = new ArrayList<>();
        for (SyncRequest.Watermark watermark : requested) {
            if (!returned.contains(watermark.getConversationId())) {
                missing.add(watermark.getConversationId());
            }
        }
        return missing;
    }

    private static List<SyncRequest> splitWatermarks(
            List<SyncRequest.Watermark> watermarks, @Nullable String since) {
        List<SyncRequest> requests = new ArrayList<>(
                (watermarks.size() + MAX_WATERMARKS_PER_REQUEST - 1)
                        / MAX_WATERMARKS_PER_REQUEST);
        for (int start = 0; start < watermarks.size(); start += MAX_WATERMARKS_PER_REQUEST) {
            int end = Math.min(start + MAX_WATERMARKS_PER_REQUEST, watermarks.size());
            requests.add(new SyncRequest(
                    new ArrayList<>(watermarks.subList(start, end)),
                    DELTA_LIMIT_PER_CONVERSATION,
                    since));
        }
        return requests;
    }

    private Single<SyncResponse> fetchDeltaResponse(SyncRequest request) {
        return ApiResult.safeApiCall(messageApi.syncDeltas(request))
                .flatMap(apiResult -> {
                    if (apiResult instanceof ApiResult.Success<?> success) {
                        SyncResponse response = (SyncResponse) success.getBody();
                        if (response != null) {
                            return Single.just(response);
                        }
                    }
                    return Single.error(new IOException("sync delta batch failed"));
                });
    }

    private Single<SyncCursor> loadCursorState(long expectedEpoch) {
        if (!tokenRefresher.isSessionCurrent(expectedEpoch)) {
            return Single.error(new CancellationException("Sync session changed"));
        }
        return tokenStore.readTokens()
                .map(tokens -> Optional.ofNullable(
                        TimeAndJwt.userIdFromJwt(tokens.getAccessToken(), moshi)))
                .defaultIfEmpty(Optional.empty())
                .flatMap(userId -> {
                    if (userId.isEmpty()) {
                        return Single.just(new SyncCursor(null, null));
                    }
                    String value = userId.get();
                    return tokenStore.readSyncServerTime(value)
                            .map(serverTime -> new SyncCursor(value, serverTime))
                            .defaultIfEmpty(new SyncCursor(value, null));
                })
                .flatMap(cursor -> tokenRefresher.isSessionCurrent(expectedEpoch)
                        ? Single.just(cursor)
                        : Single.error(new CancellationException("Sync session changed")));
    }

    private Completable persistServerTime(
            SyncCursor cursor, @Nullable String serverTime, long expectedEpoch) {
        if (cursor.userId == null || serverTime == null) {
            return Completable.complete();
        }
        return Completable.fromAction(() -> {
            boolean persisted = tokenRefresher.runIfSessionCurrent(expectedEpoch, () ->
                    tokenStore.saveSyncServerTime(cursor.userId, serverTime)
                            .blockingAwait());
            if (!persisted) {
                throw new CancellationException("Sync session changed before cursor write");
            }
            lastServerTime = serverTime;
        });
    }

    private static final class SyncCursor {
        @Nullable private final String userId;
        @Nullable private final String serverTime;

        private SyncCursor(@Nullable String userId, @Nullable String serverTime) {
            this.userId = userId;
            this.serverTime = serverTime;
        }
    }

    private List<ConversationWatermark> mergeWatermarks(
            List<ConversationWatermark> msgWatermarks,
            List<ConversationWatermark> convWatermarks) {
        Map<String, Long> map = new HashMap<>();
        for (ConversationWatermark wm : msgWatermarks) {
            map.put(wm.getConversationId(), Math.max(map.getOrDefault(wm.getConversationId(), 0L), wm.getMaxSeq()));
        }
        for (ConversationWatermark wm : convWatermarks) {
            map.put(wm.getConversationId(), Math.max(map.getOrDefault(wm.getConversationId(), 0L), wm.getMaxSeq()));
        }
        List<ConversationWatermark> merged = new ArrayList<>(map.size());
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            merged.add(new ConversationWatermark(entry.getKey(), entry.getValue()));
        }
        return merged;
    }

    private void applyDeltaBundle(
            SyncResponse response,
            List<SyncRequest.Watermark> requested,
            @Nullable String ownerUserId,
            long expectedEpoch) {
        if (response == null) {
            return;
        }
        List<String> missing = missingConversations(requested, response);
        if (response.getDeltas().isEmpty() && missing.isEmpty()) {
            return;
        }

        final String myUserId = ownerUserId == null ? "" : ownerUserId;

        boolean applied = tokenRefresher.runIfSessionCurrent(expectedEpoch, () ->
                appDatabase.runInTransaction(() -> {
                    for (SyncResponse.ConversationDeltaDto delta : response.getDeltas()) {
                        String conversationId = delta.getConversationId();
                        if (conversationId == null) continue;

                        // 0. Apply the caller's own per-member preferences. The server
                        //    carries them on every delta so a change made on another
                        //    device reaches Room without a separate inbox fetch.
                        if (delta.getMuted() != null) {
                            boolean muted = Boolean.TRUE.equals(delta.getMuted());
                            Long mutedUntil = muted
                                    ? TimeAndJwt.isoToEpochMsOrNull(delta.getMutedUntil())
                                    : null;
                            conversationDao.updateMute(conversationId, muted, mutedUntil).blockingAwait();
                        }
                        if (delta.getArchived() != null) {
                            conversationDao.updateArchived(
                                    conversationId, Boolean.TRUE.equals(delta.getArchived())).blockingAwait();
                        }

                        // 1. Ingest new messages
                        if (!delta.getNewMessages().isEmpty()) {
                            List<MessageEntity> newEntities = new ArrayList<>();
                            List<String> reactionMsgIds = new ArrayList<>();
                            List<MessageReactionEntity> reactionEntities = new ArrayList<>();
                            int incoming = 0;

                            for (MessageDto dto : delta.getNewMessages()) {
                                MessageEntity entity = toEntity(dto, conversationId);
                                if (entity != null) {
                                    boolean isNew = messageDao.byId(entity.getId()).blockingGet() == null;
                                    newEntities.add(entity);
                                    if (isNew && entity.getDeletedAtEpochMs() == null
                                            && !myUserId.equals(entity.getSenderId())) {
                                        incoming++;
                                    }
                                    if (dto.getReactions() != null && !dto.getReactions().isEmpty()) {
                                        reactionMsgIds.add(entity.getId());
                                        for (ReactionSummaryDto summary : dto.getReactions()) {
                                            MessageReactionEntity rEntity = toReactionEntity(
                                                    summary, entity.getId(), conversationId, myUserId);
                                            if (rEntity != null) reactionEntities.add(rEntity);
                                        }
                                    }
                                }
                            }

                            if (!newEntities.isEmpty()) {
                                messageDao.upsertAll(newEntities).blockingAwait();
                                MessageEntity last = newEntities.get(newEntities.size() - 1);
                                conversationDao.updateLastMessage(
                                        conversationId, last.getSeq(),
                                        last.getContent() != null ? last.getContent() : last.getType(),
                                        last.getSenderDisplayName() != null ? last.getSenderDisplayName() : "",
                                        last.getCreatedAt()
                                ).blockingAwait();
                            }
                            if (incoming > 0) {
                                conversationDao.incrementUnread(conversationId, incoming).blockingAwait();
                            }
                            for (MessageEntity entity : newEntities) {
                                if (entity.getClientMessageId() == null
                                        || !myUserId.equals(entity.getSenderId())) {
                                    continue;
                                }
                                MessageEntity committed = messageDao.byId(entity.getId()).blockingGet();
                                if (committed != null && myUserId.equals(committed.getSenderId())
                                        && conversationId.equals(committed.getConversationId())
                                        && (committed.getClientMessageId() == null
                                        || entity.getClientMessageId().equals(committed.getClientMessageId()))) {
                                    appDatabase.outboxDao().delete(
                                            entity.getClientMessageId(), myUserId).blockingAwait();
                                }
                            }
                            if (!reactionMsgIds.isEmpty()) {
                                reactionDao.replaceForMessagesInTransaction(reactionMsgIds, reactionEntities);
                            }
                        }

                        // 2. Ingest edited messages
                        if (!delta.getEditedMessages().isEmpty()) {
                            for (MessageDto dto : delta.getEditedMessages()) {
                                MessageEntity entity = toEntity(dto, conversationId);
                                if (entity != null) {
                                    messageDao.update(entity).blockingAwait();
                                    conversationDao.updateLastMessageContent(
                                            conversationId, entity.getSeq(),
                                            entity.getContent() != null ? entity.getContent() : entity.getType()
                                    ).blockingAwait();
                                }
                            }
                        }

                        // 3. Ingest deleted message IDs
                        if (!delta.getDeletedMessageIds().isEmpty()) {
                            for (String deletedId : delta.getDeletedMessageIds()) {
                                MessageEntity existing = messageDao.byId(deletedId).blockingGet();
                                if (existing != null && existing.getDeletedAtEpochMs() == null) {
                                    MessageEntity tombstone = new MessageEntity(
                                            existing.getId(), existing.getConversationId(), existing.getSeq(),
                                            existing.getSenderId(), existing.getSenderDisplayName(), existing.getType(),
                                            "", existing.getClientMessageId(), existing.getReplyToId(),
                                            existing.getReplyToSenderName(), existing.getReplyToSnippet(),
                                            existing.getCreatedAt(), null, existing.getEditedAtEpochMs(),
                                            System.currentTimeMillis()
                                    );
                                    messageDao.update(tombstone).blockingAwait();
                                    reactionDao.deleteForMessageInTransaction(deletedId);
                                    messageDao.clearReplySnippets(deletedId).blockingAwait();
                                    // Keep the inbox preview neutral when the deleted message was the
                                    // conversation's last one, as the live delete path already does.
                                    ConversationEntity row = conversationDao.byId(existing.getConversationId()).blockingGet();
                                    if (row != null && row.getLastMessageSeq() == existing.getSeq()) {
                                        conversationDao.markLastMessageDeleted(existing.getConversationId()).blockingAwait();
                                    }
                                }
                            }
                        }

                        // 4. Ingest reaction updates. A full-refresh delta carries an
                        // empty partial page because the event log was purged; applying
                        // it would erase nothing but cannot repair local state either,
                        // so history reload remains the authoritative path.
                        if (!delta.isReactionsFullRefresh() && !delta.getReactionUpdates().isEmpty()) {
                            for (ReactionUpdateDto update : delta.getReactionUpdates()) {
                                String msgId = update.getMessageId();
                                if (msgId != null) {
                                    List<MessageReactionEntity> rEntities = new ArrayList<>();
                                    for (ReactionSummaryDto summary : update.getReactions()) {
                                        MessageReactionEntity rEntity = toReactionEntity(
                                                summary, msgId, conversationId, myUserId);
                                        if (rEntity != null) rEntities.add(rEntity);
                                    }
                                    reactionDao.replaceForMessageInTransaction(msgId, rEntities);
                                }
                            }
                        }

                        // 5. Apply caller-only member flags (pinned, pinnedAt).
                        if (delta.getPinned() != null) {
                            boolean pinned = Boolean.TRUE.equals(delta.getPinned());
                            Long pinnedAt = delta.getPinnedAt() != null
                                    ? TimeAndJwt.isoToEpochMsOrNull(delta.getPinnedAt())
                                    : null;
                            conversationDao.updatePin(conversationId, pinned, pinnedAt).blockingAwait();
                        }

                        // 6. Peer receipt watermarks advance ONLY from the peer's own read/delivered
                        //    frames; a delta bundle says nothing about what the peer has read.
                    }
                    for (String conversationId : missing) {
                        LocalConversationCleaner.deleteInTransaction(appDatabase, conversationId, myUserId);
                    }
                }));
        if (!applied) {
            throw new CancellationException("Sync session changed before Room transaction");
        }
    }

    @Nullable
    private MessageEntity toEntity(MessageDto dto, @Nullable String fallbackConversationId) {
        String conversationId = dto.getConversationId() != null ? dto.getConversationId() : fallbackConversationId;
        if (conversationId == null) return null;
        boolean deleted = dto.getDeletedAt() != null && !dto.getDeletedAt().isBlank();
        String attachmentsJson = null;
        if (!deleted && dto.getAttachments() != null && !dto.getAttachments().isEmpty()) {
            try {
                attachmentsJson = attachmentsAdapter.toJson(dto.getAttachments());
            } catch (RuntimeException ignored) {
                attachmentsJson = null;
            }
        }
        return new MessageEntity(
                dto.getId(),
                conversationId,
                dto.getSeq() == null ? 0L : dto.getSeq(),
                Optional.ofNullable(dto.getResolvedSenderId()).orElse(""),
                dto.getResolvedSenderName(),
                dto.getResolvedType(),
                deleted ? "" : dto.getContent(),
                dto.getClientMessageId(),
                dto.getResolvedReplyToId(),
                dto.getReplyTo() == null ? null : dto.getReplyTo().getResolvedSenderName(),
                dto.getReplyTo() == null ? null : dto.getReplyTo().getResolvedSnippet(),
                TimeAndJwt.isoToEpochMs(dto.getCreatedAt()),
                attachmentsJson,
                TimeAndJwt.isoToEpochMsOrNull(dto.getEditedAt()),
                  TimeAndJwt.isoToEpochMsOrNull(dto.getDeletedAt()),
                  deleted ? "[]" : MessageMentionCodec.encode(dto.getMentions()),
                  !deleted && dto.isMentionAll()
        );
    }

    @Nullable
    private MessageReactionEntity toReactionEntity(
            ReactionSummaryDto summary, String messageId, String conversationId, @Nullable String myId) {
        String emoji = summary.getEmoji();
        if (emoji == null || emoji.isBlank()) return null;
        Integer count = summary.getCount();
        Boolean reactedByMe = summary.getReactedByMe();
        if (reactedByMe == null) reactedByMe = summary.getByMe();
        if (reactedByMe == null) reactedByMe = myId != null && summary.getUserIds().contains(myId);
        return new MessageReactionEntity(
                messageId,
                conversationId,
                emoji,
                Math.max(0, count == null ? summary.getUserIds().size() : count),
                Boolean.TRUE.equals(reactedByMe)
        );
    }
}
