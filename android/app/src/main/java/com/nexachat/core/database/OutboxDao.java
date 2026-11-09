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

/** Access to the optimistic-send queue. */
@Dao
public interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable insert(OutboxEntity item);

    @Query("UPDATE outbox SET state = :state WHERE clientMessageId = :clientMessageId")
    Completable updateState(String clientMessageId, String state);

    /** Whole queue oldest-first; the UI filters by conversation. */
    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    Flowable<List<OutboxEntity>> pendingOrdered();

    /** Flush order: only SENDING rows, oldest first. */
    @Query("SELECT * FROM outbox WHERE state = 'SENDING' ORDER BY createdAt ASC")
    Single<List<OutboxEntity>> pendingList();

    @Query("SELECT * FROM outbox WHERE clientMessageId = :clientMessageId")
    Maybe<OutboxEntity> byId(String clientMessageId);

    @Query("DELETE FROM outbox WHERE clientMessageId = :clientMessageId")
    Completable delete(String clientMessageId);

    @Query("DELETE FROM outbox")
    Completable clear();

    /** Drops queued sends that can never be accepted after membership loss. */
    @Query("DELETE FROM outbox WHERE conversationId = :conversationId")
    Completable deleteForConversation(String conversationId);
}
