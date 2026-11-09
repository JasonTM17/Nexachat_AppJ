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

    @Query("UPDATE outbox SET state = :state WHERE clientMessageId = :clientMessageId "
            + "AND ownerUserId = :ownerUserId")
    Completable updateState(String clientMessageId, String ownerUserId, String state);

    /** Assigns upgrade-era rows only after startup identifies their stored session owner. */
    @Query("UPDATE outbox SET ownerUserId = :ownerUserId, ownerResolved = 1 "
            + "WHERE ownerUserId = '' AND ownerResolved = 0")
    Completable assignLegacyOwner(String ownerUserId);

    /** Permanently closes ownership binding for rows whose former account is unknowable. */
    @Query("UPDATE outbox SET ownerResolved = 1 WHERE ownerUserId = '' AND ownerResolved = 0")
    Completable sealUnassignedLegacyOwners();

    /** The active account's queue, oldest-first; the UI filters by conversation. */
    @Query("SELECT * FROM outbox WHERE ownerUserId = :ownerUserId ORDER BY createdAt ASC")
    Flowable<List<OutboxEntity>> pendingOrdered(String ownerUserId);

    /** Flush order: only SENDING rows, oldest first. */
    @Query("SELECT * FROM outbox WHERE ownerUserId = :ownerUserId "
            + "AND state = 'SENDING' ORDER BY createdAt ASC")
    Single<List<OutboxEntity>> pendingList(String ownerUserId);

    @Query("SELECT * FROM outbox WHERE clientMessageId = :clientMessageId "
            + "AND ownerUserId = :ownerUserId")
    Maybe<OutboxEntity> byId(String clientMessageId, String ownerUserId);

    @Query("DELETE FROM outbox WHERE clientMessageId = :clientMessageId "
            + "AND ownerUserId = :ownerUserId")
    Completable delete(String clientMessageId, String ownerUserId);

    @Query("DELETE FROM outbox")
    Completable clear();

    /** Drops queued sends that can never be accepted after membership loss. */
    @Query("DELETE FROM outbox WHERE conversationId = :conversationId "
            + "AND ownerUserId = :ownerUserId")
    Completable deleteForConversation(String conversationId, String ownerUserId);
}
