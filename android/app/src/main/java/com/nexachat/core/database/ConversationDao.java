package com.nexachat.core.database;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;

@Dao
public interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable upsert(ConversationEntity conversation);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable upsertAll(List<ConversationEntity> conversations);

    /** Inbox ordering: pinned conversations first (by pinnedAt desc), then newest activity. */
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, COALESCE(pinnedAt, 0) DESC, COALESCE(lastMessageAt, 0) DESC, id ASC")
    Flowable<List<ConversationEntity>> inboxFlow();

    @Query("UPDATE conversations SET pinned = :pinned, pinnedAt = :pinnedAt WHERE id = :conversationId")
    Completable updatePin(String conversationId, boolean pinned, Long pinnedAt);

    @Query("SELECT id AS conversationId, COALESCE(lastMessageSeq, 0) AS maxSeq FROM conversations")
    Single<List<ConversationWatermark>> getAllWatermarks();

    /**
     * Observable single-row query represented as a list so the empty-row case
     * remains observable without emitting null, which RxJava forbids.
     */
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    Flowable<List<ConversationEntity>> observeById(String conversationId);

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    Maybe<ConversationEntity> byId(String conversationId);

    @Query("UPDATE conversations SET labelIdsCsv = :labelIdsCsv WHERE id = :conversationId")
    Completable updateLabelIds(String conversationId, String labelIdsCsv);

    /** Removes one UUID token from every cached caller-owned assignment list. */
    @Query("UPDATE conversations SET labelIdsCsv = "
            + "trim(replace(',' || labelIdsCsv || ',', ',' || :labelId || ',', ','), ',') "
            + "WHERE instr(',' || labelIdsCsv || ',', ',' || :labelId || ',') > 0")
    Completable removeLabelIdFromAll(String labelId);

    /** Reads client-only columns that a REPLACE upsert would otherwise wipe. */
    @Query("SELECT * FROM conversations WHERE id IN (:ids)")
    Single<List<ConversationEntity>> byIds(List<String> ids);

    /** Monotonic peer watermark: a replay cannot rewind a visible receipt. */
    @Query(
            "UPDATE conversations SET peerReadSeq = MAX(peerReadSeq, :seq) "
                    + "WHERE id = :conversationId")
    Completable bumpPeerReadSeq(String conversationId, long seq);

    /** Monotonic peer delivered watermark: a replay cannot rewind a delivered receipt. */
    @Query(
            "UPDATE conversations SET peerDeliveredSeq = MAX(peerDeliveredSeq, :seq) "
                    + "WHERE id = :conversationId")
    Completable bumpPeerDeliveredSeq(String conversationId, long seq);

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    Completable clearUnread(String conversationId);

    /** Out-of-order events cannot rewind the denormalized inbox preview. */
    @Query(
            "UPDATE conversations SET lastMessageSeq = :seq, lastMessageContent = :content, "
                    + "lastMessageSenderName = :senderName, lastMessageAt = :at, "
                    + "lastMessageDeleted = 0 "
                    + "WHERE id = :conversationId AND :seq > lastMessageSeq")
    Completable updateLastMessage(
            String conversationId, long seq, String content, String senderName, long at);

    /** An edit refreshes the snippet only while the same message remains last. */
    @Query(
            "UPDATE conversations SET lastMessageContent = :content, lastMessageDeleted = 0 "
                    + "WHERE id = :conversationId AND lastMessageSeq = :seq")
    Completable updateLastMessageContent(String conversationId, long seq, String content);

    /** Marks the deleted last message with a neutral localized snippet. */
    @Query(
            "UPDATE conversations SET lastMessageContent = NULL, lastMessageDeleted = 1 "
                    + "WHERE id = :conversationId")
    Completable markLastMessageDeleted(String conversationId);

    @Query("UPDATE conversations SET unreadCount = unreadCount + :delta WHERE id = :conversationId")
    Completable incrementUnread(String conversationId, int delta);

    /** Caller-scoped mute preference; {@code mutedUntil} is null for a forever mute. */
    @Query(
            "UPDATE conversations SET muted = :muted, mutedUntilEpochMs = :mutedUntilEpochMs "
                    + "WHERE id = :conversationId")
    Completable updateMute(String conversationId, boolean muted, @Nullable Long mutedUntilEpochMs);

    /** Caller-scoped archive preference. */
    @Query("UPDATE conversations SET archived = :archived WHERE id = :conversationId")
    Completable updateArchived(String conversationId, boolean archived);

    /** Caller-scoped per-conversation AI opt-out preference. */
    @Query("UPDATE conversations SET aiOptOut = :aiOptOut WHERE id = :conversationId")
    Completable updateAiOptOut(String conversationId, boolean aiOptOut);

    @Query("DELETE FROM conversations")
    Completable clear();

    /** Removes one conversation after the server proves it is no longer accessible. */
    @Query("DELETE FROM conversations WHERE id = :conversationId")
    Completable deleteById(String conversationId);

    /** Clears only the default inbox rows, leaving archived rows intact. */
    @Query("DELETE FROM conversations WHERE archived = 0")
    Completable clearInbox();

    /** Clears only archived rows, leaving the default inbox intact. */
    @Query("DELETE FROM conversations WHERE archived = 1")
    Completable clearArchived();

    /** Reconciles the default inbox; archived rows are not part of this walk. */
    @Query("DELETE FROM conversations WHERE archived = 0 AND id NOT IN (:ids)")
    Completable deleteInboxExcept(List<String> ids);

    /** Reconciles archived rows; default-inbox rows are not part of this walk. */
    @Query("DELETE FROM conversations WHERE archived = 1 AND id NOT IN (:ids)")
    Completable deleteArchivedExcept(List<String> ids);

    /** Reconciles to the authoritative full inbox set; empty ids use clear(). */
    @Query("DELETE FROM conversations WHERE id NOT IN (:ids)")
    Completable deleteAllExcept(List<String> ids);
}
