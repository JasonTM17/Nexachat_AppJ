package com.nexachat.core.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;

/** Conversation pins, ordered newest first for the banner projection. */
@Dao
public abstract class PinDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract Completable upsert(PinnedMessageEntity pin);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract Completable upsertAll(List<PinnedMessageEntity> pins);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract void upsertAllInTransaction(List<PinnedMessageEntity> pins);

    @Query(
            "SELECT * FROM pinned_messages WHERE conversationId = :conversationId "
                    + "ORDER BY pinnedAt DESC")
    public abstract Flowable<List<PinnedMessageEntity>> pinnedFor(String conversationId);

    @Query(
            "SELECT * FROM pinned_messages WHERE conversationId = :conversationId "
                    + "AND messageId = :messageId")
    public abstract Maybe<PinnedMessageEntity> find(String conversationId, String messageId);

    @Query(
            "DELETE FROM pinned_messages WHERE conversationId = :conversationId "
                    + "AND messageId = :messageId")
    public abstract Completable delete(String conversationId, String messageId);

    @Query("DELETE FROM pinned_messages WHERE conversationId = :conversationId")
    public abstract Completable deleteForConversation(String conversationId);

    @Query("DELETE FROM pinned_messages WHERE conversationId = :conversationId")
    protected abstract void deleteForConversationInTransaction(String conversationId);

    @Query("DELETE FROM pinned_messages")
    public abstract Completable clear();

    /** GET /pins is authoritative; replace the full set per conversation. */
    @Transaction
    public void replaceForConversationInTransaction(
            String conversationId, List<PinnedMessageEntity> pins) {
        deleteForConversationInTransaction(conversationId);
        upsertAllInTransaction(pins);
    }

    /** Runs the synchronous Room transaction on a background scheduler. */
    public Completable replaceForConversation(
            String conversationId, List<PinnedMessageEntity> pins) {
        return Completable.fromAction(
                        () -> replaceForConversationInTransaction(conversationId, pins))
                .subscribeOn(Schedulers.io());
    }
}
