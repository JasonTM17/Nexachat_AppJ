package com.nexachat.core.database;

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

    /** Inbox ordering: newest activity first, empty conversations last. */
    @Query("SELECT * FROM conversations ORDER BY COALESCE(lastMessageAt, 0) DESC")
    Flowable<List<ConversationEntity>> inboxFlow();

    /**
     * Observable single-row query represented as a list so the empty-row case
     * remains observable without emitting null, which RxJava forbids.
     */
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    Flowable<List<ConversationEntity>> observeById(String conversationId);

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    Maybe<ConversationEntity> byId(String conversationId);

    /** Reads client-only columns that a REPLACE upsert would otherwise wipe. */
    @Query("SELECT * FROM conversations WHERE id IN (:ids)")
    Single<List<ConversationEntity>> byIds(List<String> ids);

    /** Monotonic peer watermark: a replay cannot rewind a visible receipt. */
    @Query(
            "UPDATE conversations SET peerReadSeq = MAX(peerReadSeq, :seq) "
                    + "WHERE id = :conversationId")
    Completable bumpPeerReadSeq(String conversationId, long seq);

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

    @Query("DELETE FROM conversations")
    Completable clear();

    /** Reconciles to the authoritative full inbox set; empty ids use clear(). */
    @Query("DELETE FROM conversations WHERE id NOT IN (:ids)")
    Completable deleteAllExcept(List<String> ids);
}
