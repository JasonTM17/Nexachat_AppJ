package com.nexachat.data;

import com.nexachat.core.database.*;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.*;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Atomic v2 page persistence; never derives a committed revision from seq or wall time. */
final class SyncPageStore {
    private final AppDatabase db;
    private final TokenRefresher refresher;
    private final TokenStore tokens;
    private final Moshi moshi;
    private final MessageStateApplier messages;
    Runnable beforeCommit = () -> {};
    Runnable afterCommit = () -> {};
    private volatile long legacyEpoch = Long.MIN_VALUE;

    SyncPageStore(AppDatabase db, TokenRefresher refresher, TokenStore tokens, Moshi moshi) {
        this.db = db;
        this.refresher = refresher;
        this.tokens = tokens;
        this.moshi = moshi;
        messages = new MessageStateApplier(db, moshi);
    }

    SyncCursorEntity read(String owner, String conversation) {
        SyncCursorEntity cursor = db.syncCursorDao().find(owner, conversation);
        return cursor != null ? cursor : new SyncCursorEntity(owner, conversation, 0, 0,
                null, null, null, true);
    }

    void legacyFallback(String owner, long epoch, List<ConversationWatermark> watermarks) {
        fenced(owner, epoch, () -> {
            db.runInTransaction(() -> {
            for (ConversationWatermark watermark : watermarks) {
                db.syncCursorDao().delete(owner, watermark.getConversationId());
                db.messageDao().clearSyncVersionsForConversation(watermark.getConversationId());
            }
            });
            legacyEpoch = epoch;
        });
    }

    /** Validate the complete envelope before any v2 writes; partial negotiation falls back as a unit. */
    static void validate(SyncResponse response, Map<String, SyncCursorEntity> requested) throws LegacyRequired {
        if (response.getDeltasField() == null || !Integer.valueOf(2).equals(response.getSyncVersion())
                || TimeAndJwt.isoToEpochMsOrNull(response.getServerTime()) == null) throw new LegacyRequired();
        Set<String> seen = new HashSet<>();
        for (SyncResponse.ConversationDeltaDto delta : response.getDeltas()) {
            SyncCursorEntity current = requested.get(delta.getConversationId());
            if (current == null || !seen.add(delta.getConversationId()) || delta.getChanges() == null
                    || delta.getChangeCursor() == null || delta.getChangeUpperBound() == null
                    || delta.getGeneration() == null || delta.getGeneration() <= 0
                    || delta.getHasMoreField() == null || delta.getChangeHasMore() == null || delta.getReset() == null
                    || delta.getChangeCursor() < 0 || delta.getChangeUpperBound() < delta.getChangeCursor()
                    || delta.getChanges().size() > 50
                    || (delta.getReactionCursor() != null && delta.getReactionCursor() < 0)) throw new LegacyRequired();
            if (delta.getGeneration() < current.generation
                    || (!delta.getReset() && delta.getChangeCursor() < current.changeCursor)) {
                throw new CancellationException("Discarded stale sync generation or cursor");
            }
            if (Boolean.TRUE.equals(delta.getReset())) {
                if (delta.getChangeCursor() != 0 || !delta.getChanges().isEmpty()
                        || !delta.getNewMessages().isEmpty() || !delta.getEditedMessages().isEmpty()
                        || !delta.getDeletedMessageIds().isEmpty() || !delta.getReactionUpdates().isEmpty()
                        || delta.isHasMore() || delta.getChangeHasMore()) throw new LegacyRequired();
                if (delta.getGeneration() < current.generation) throw new LegacyRequired();
                if (delta.getGeneration() == current.generation && current.changeCursor == 0) {
                    throw new IllegalStateException("v2 reset made no progress");
                }
                continue;
            }
            if ((current.generation > 0 && delta.getGeneration() != current.generation)
                    || delta.getChangeCursor() < current.changeCursor
                    || (delta.getChangeHasMore() && !delta.isHasMore())
                    || (!delta.getChangeHasMore() && !delta.getChangeCursor().equals(delta.getChangeUpperBound()))) {
                throw new LegacyRequired();
            }
            long previous = current.changeCursor;
            Set<String> ids = new HashSet<>();
            for (MessageDto row : delta.getChanges()) {
                if (row == null || row.getId() == null || row.getId().isBlank() || !ids.add(row.getId())
                        || row.getSeq() == null || row.getSeq() <= 0 || row.getChangeVersion() == null
                        || row.getChangeVersion() <= previous || row.getChangeVersion() > delta.getChangeCursor()
                        || (row.getConversationId() != null && !row.getConversationId().equals(delta.getConversationId()))
                        || (row.getGeneration() != null && !row.getGeneration().equals(delta.getGeneration()))) {
                    throw new LegacyRequired();
                }
                previous = row.getChangeVersion();
            }
            if (delta.getChangeHasMore() && (delta.getChanges().isEmpty()
                    || delta.getChangeCursor() != previous)) {
                throw new IllegalStateException("Truncated change page did not stop at its last row");
            }
            if (current.reactionCursor != null && delta.getReactionCursor() != null
                    && delta.getReactionCursor() < current.reactionCursor) {
                throw new IllegalStateException("Reaction cursor regressed");
            }
        }
    }

    List<String> apply(String owner, long epoch, Map<String, SyncCursorEntity> requested,
            SyncResponse response) {
        List<String> pending = new ArrayList<>();
        fenced(owner, epoch, () -> db.runInTransaction(() -> {
            // Reject pages whose cache/cursor scope changed while HTTP was in flight.
            for (SyncCursorEntity expected : requested.values()) {
                if (!same(expected, read(owner, expected.conversationId))
                        || db.conversationDao().byId(expected.conversationId).blockingGet() == null) {
                    throw new CancellationException("Sync cache changed before page commit");
                }
            }
            Set<String> returned = new HashSet<>();
            for (SyncResponse.ConversationDeltaDto delta : response.getDeltas()) {
                String conversation = delta.getConversationId();
                returned.add(conversation);
                SyncCursorEntity current = requested.get(conversation);
                if (delta.getReset()) {
                    // Reset never deletes queued sends or another account's outbox.
                    db.messageDao().deleteForConversation(conversation).blockingAwait();
                    db.reactionDao().deleteForConversation(conversation).blockingAwait();
                    db.pinDao().deleteForConversation(conversation).blockingAwait();
                    db.syncCursorDao().delete(owner, conversation);
                    put(new SyncCursorEntity(owner, conversation, delta.getGeneration(), 0,
                            null, null, null, true));
                    pending.add(conversation);
                    continue;
                }
                if (delta.isReactionsFullRefresh() && !current.rebuildingReactions) {
                    db.reactionDao().deleteForConversation(conversation).blockingAwait();
                    put(new SyncCursorEntity(owner, conversation, delta.getGeneration(), 0,
                            null, null, response.getServerTime(), true));
                    pending.add(conversation);
                    continue;
                }
                if (current.rebuildingReactions && current.changeCursor == 0) {
                    db.reactionDao().deleteForConversation(conversation).blockingAwait();
                }
                for (MessageDto row : delta.getChanges()) {
                    messages.apply(row, conversation, delta.getGeneration(), owner, current.rebuildingReactions);
                }
                if (!delta.isReactionsFullRefresh()) {
                    for (ReactionUpdateDto update : delta.getReactionUpdates()) {
                        if (update.getMessageId() == null) continue;
                        MessageEntity row = db.messageDao().byId(update.getMessageId()).blockingGet();
                        if (row != null && conversation.equals(row.getConversationId())
                                && row.getDeletedAtEpochMs() == null) {
                            db.reactionDao().replaceForMessageInTransaction(update.getMessageId(),
                                    MessageStateApplier.reactions(update.getReactions(), update.getMessageId(),
                                            conversation, owner));
                        }
                    }
                }
                preferences(delta);
                String baseline = current.reactionBaseline != null
                        ? current.reactionBaseline : response.getServerTime();
                boolean rebuilding = current.rebuildingReactions;
                String since = current.reactionSince;
                Long reactionCursor = delta.getReactionCursor();
                boolean more = delta.isHasMore() || delta.getChangeHasMore();
                boolean replayBaseline = rebuilding && !delta.getChangeHasMore() && !more;
                if (replayBaseline || !more) {
                    since = baseline;
                    baseline = null;
                    reactionCursor = null;
                    rebuilding = false;
                }
                if (more && delta.getChangeCursor() == current.changeCursor
                        && Objects.equals(reactionCursor, current.reactionCursor)
                        && !replayBaseline) throw new IllegalStateException("v2 sync page made no progress");
                put(new SyncCursorEntity(owner, conversation, delta.getGeneration(), delta.getChangeCursor(),
                        since, reactionCursor, baseline, rebuilding));
                if (more || replayBaseline) pending.add(conversation);
            }
            for (String conversation : requested.keySet()) {
                if (!returned.contains(conversation)) {
                    LocalConversationCleaner.deleteInTransaction(db, conversation, owner);
                    db.syncCursorDao().delete(owner, conversation);
                }
            }
            beforeCommit.run();
        }));
        if (legacyEpoch == epoch) legacyEpoch = Long.MIN_VALUE;
        afterCommit.run();
        return pending;
    }

    void lifecycle(String owner, long epoch, MessageDto dto, String conversation, boolean countUnread,
            boolean historyReactions) {
        boolean versioned = dto.getGeneration() != null || dto.getChangeVersion() != null;
        if (dto.getId() == null || (dto.getConversationId() != null && !conversation.equals(dto.getConversationId()))) return;
        if (versioned && (dto.getGeneration() == null || dto.getGeneration() <= 0
                || dto.getChangeVersion() == null || dto.getChangeVersion() <= 0)) return;
        long generation = versioned ? dto.getGeneration() : 0;
        fenced(owner, epoch, () -> db.runInTransaction(() -> {
            SyncCursorEntity cursor = db.syncCursorDao().find(owner, conversation);
            if (versioned && legacyEpoch == epoch) return;
            if (cursor != null && cursor.generation != generation) return;
            if (db.conversationDao().byId(conversation).blockingGet() == null) return;
            boolean isNew = db.messageDao().byId(dto.getId()).blockingGet() == null;
            boolean accepted = messages.apply(dto, conversation, generation, owner, historyReactions);
            if (accepted && isNew && countUnread && !owner.equals(dto.getResolvedSenderId())
                    && dto.getDeletedAt() == null) {
                db.conversationDao().incrementUnread(conversation, 1).blockingAwait();
            }
            // A retry acknowledgement proves delivery even when its content was already cached
            // by sync/history or superseded by a later edit. Never tie queue progress to repainting.
            if (owner.equals(dto.getResolvedSenderId()) && dto.getClientMessageId() != null) {
                MessageEntity committed = db.messageDao().byId(dto.getId()).blockingGet();
                if (committed != null && owner.equals(committed.getSenderId())
                        && conversation.equals(committed.getConversationId())
                        && (committed.getClientMessageId() == null
                        || dto.getClientMessageId().equals(committed.getClientMessageId()))) {
                    db.outboxDao().delete(dto.getClientMessageId(), owner).blockingAwait();
                }
            }
        }));
    }

    void deleteLifecycle(String owner, long epoch, String id, String conversation,
            long version, long generation, Long deletedAt) {
        if (version < 0 || generation < 0 || (version == 0) != (generation == 0)) return;
        fenced(owner, epoch, () -> db.runInTransaction(() -> {
            SyncCursorEntity cursor = db.syncCursorDao().find(owner, conversation);
            if (generation > 0 && legacyEpoch == epoch) return;
            if (cursor != null && cursor.generation != generation) return;
            if (db.conversationDao().byId(conversation).blockingGet() == null) return;
            messages.delete(id, conversation, version, generation, deletedAt);
        }));
    }

    private void fenced(String owner, long epoch, Runnable write) {
        boolean applied = refresher.runIfSessionCurrent(epoch, () -> {
            Tokens current = tokens.readTokens().blockingGet();
            if (current == null || !owner.equals(TimeAndJwt.userIdFromJwt(current.getAccessToken(), moshi))) {
                throw new CancellationException("Sync account changed before Room write");
            }
            write.run();
        });
        if (!applied) throw new CancellationException("Sync epoch changed before Room write");
    }

    private void put(SyncCursorEntity cursor) { db.syncCursorDao().put(cursor); }

    private void preferences(SyncResponse.ConversationDeltaDto delta) {
        String conversation = delta.getConversationId();
        if (delta.getMuted() != null) db.conversationDao().updateMute(conversation, delta.getMuted(),
                delta.getMuted() ? TimeAndJwt.isoToEpochMsOrNull(delta.getMutedUntil()) : null).blockingAwait();
        if (delta.getArchived() != null) db.conversationDao().updateArchived(conversation, delta.getArchived()).blockingAwait();
        if (delta.getPinned() != null) db.conversationDao().updatePin(conversation, delta.getPinned(),
                TimeAndJwt.isoToEpochMsOrNull(delta.getPinnedAt())).blockingAwait();
    }

    private static boolean same(SyncCursorEntity a, SyncCursorEntity b) {
        return a.generation == b.generation && a.changeCursor == b.changeCursor
                && Objects.equals(a.reactionSince, b.reactionSince)
                && Objects.equals(a.reactionCursor, b.reactionCursor)
                && Objects.equals(a.reactionBaseline, b.reactionBaseline)
                && a.rebuildingReactions == b.rebuildingReactions;
    }

    static final class LegacyRequired extends IOException {
        LegacyRequired() { super("Complete sync v2 contract unavailable; legacy sync required"); }
    }
}
