package com.nexachat.core.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.core.Single;
import java.util.List;

/**
 * Reaction summaries are authoritative per message and emoji. Whole-message
 * sets are replaced atomically rather than merged from partial rows.
 */
@Dao
public abstract class ReactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract Completable upsertAll(List<MessageReactionEntity> reactions);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract void upsertAllInTransaction(List<MessageReactionEntity> reactions);

    @Query("SELECT * FROM message_reactions WHERE conversationId = :conversationId")
    public abstract Flowable<List<MessageReactionEntity>> reactionsForConversation(
            String conversationId);

    @Query("SELECT * FROM message_reactions WHERE messageId = :messageId")
    public abstract Single<List<MessageReactionEntity>> forMessage(String messageId);

    @Query("DELETE FROM message_reactions WHERE messageId = :messageId")
    public abstract Completable deleteForMessage(String messageId);

    @Query("DELETE FROM message_reactions WHERE messageId = :messageId")
    protected abstract void deleteForMessageInTransaction(String messageId);

    @Query("DELETE FROM message_reactions WHERE messageId IN (:messageIds)")
    public abstract Completable deleteForMessages(List<String> messageIds);

    @Query("DELETE FROM message_reactions WHERE messageId IN (:messageIds)")
    protected abstract void deleteForMessagesInTransaction(List<String> messageIds);

    @Query("DELETE FROM message_reactions")
    public abstract Completable clear();

    /** Atomic set replacement for one message. */
    @Transaction
    public void replaceForMessageInTransaction(
            String messageId, List<MessageReactionEntity> reactions) {
        deleteForMessageInTransaction(messageId);
        upsertAllInTransaction(reactions);
    }

    /** Runs the synchronous Room transaction on a background scheduler. */
    public Completable replaceForMessage(
            String messageId, List<MessageReactionEntity> reactions) {
        return Completable.fromAction(() -> replaceForMessageInTransaction(messageId, reactions))
                .subscribeOn(Schedulers.io());
    }

    /**
     * Batch history-page replacement. Only messages carrying reactions are
     * passed in messageIds; an absent field must not wipe local summaries.
     */
    @Transaction
    public void replaceForMessagesInTransaction(
            List<String> messageIds, List<MessageReactionEntity> reactions) {
        if (messageIds.isEmpty()) return;
        deleteForMessagesInTransaction(messageIds);
        upsertAllInTransaction(reactions);
    }

    /** Runs the synchronous Room transaction on a background scheduler. */
    public Completable replaceForMessages(
            List<String> messageIds, List<MessageReactionEntity> reactions) {
        return Completable.fromAction(() -> replaceForMessagesInTransaction(messageIds, reactions))
                .subscribeOn(Schedulers.io());
    }
}
