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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertNew(OutboxEntity item);

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

    /** Flush order includes uploads that still need preparation. */
    @Query("SELECT * FROM outbox WHERE ownerUserId = :ownerUserId "
            + "AND state IN ('SENDING', 'WAITING_UPLOAD', 'UPLOAD_READY') "
            + "ORDER BY createdAt ASC, clientMessageId ASC")
    Single<List<OutboxEntity>> pendingList(String ownerUserId);

    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE ownerUserId = :ownerUserId "
            + "AND conversationId = :conversationId AND createdAt < :createdAt "
            + "AND state IN ('WAITING_UPLOAD', 'UPLOAD_READY'))")
    Single<Boolean> hasEarlierUpload(String ownerUserId, String conversationId, long createdAt);

    @Query("UPDATE outbox SET state = 'UPLOAD_READY', type = :type, "
            + "attachmentsJson = :attachmentsJson WHERE clientMessageId = :id "
            + "AND ownerUserId = :ownerUserId AND state = 'WAITING_UPLOAD'")
    int markUploadReady(String id, String ownerUserId, String type, String attachmentsJson);

    @Query("UPDATE outbox SET state = 'FAILED_UPLOAD' WHERE clientMessageId = :id "
            + "AND ownerUserId = :ownerUserId AND state = 'WAITING_UPLOAD'")
    void failUpload(String id, String ownerUserId);

    @Query("UPDATE outbox SET state = 'FAILED_UPLOAD_SEND' WHERE clientMessageId = :id "
            + "AND ownerUserId = :ownerUserId AND state = 'UPLOAD_READY'")
    void failUploadedSend(String id, String ownerUserId);

    @Query("UPDATE outbox SET state = :state, createdAt = :createdAt "
            + "WHERE clientMessageId = :id AND ownerUserId = :ownerUserId "
            + "AND state = :expectedState")
    void requeueUpload(String id, String ownerUserId, String expectedState, String state, long createdAt);

    @Query("SELECT ownerUserId, clientMessageId FROM outbox "
            + "WHERE state IN ('WAITING_UPLOAD', 'FAILED_UPLOAD')")
    List<StagedUploadRef> stagedUploadRefs();

    record StagedUploadRef(String ownerUserId, String clientMessageId) {}

    @Query("SELECT * FROM outbox WHERE clientMessageId = :clientMessageId "
            + "AND ownerUserId = :ownerUserId")
    Maybe<OutboxEntity> byId(String clientMessageId, String ownerUserId);

    @Query("DELETE FROM outbox WHERE clientMessageId = :clientMessageId "
            + "AND ownerUserId = :ownerUserId")
    Completable delete(String clientMessageId, String ownerUserId);

    /** Synchronous variant for {@code AppDatabase.runInTransaction} bodies (issue #184). */
    @Query("DELETE FROM outbox WHERE clientMessageId = :clientMessageId "
            + "AND ownerUserId = :ownerUserId")
    void deleteInTransaction(String clientMessageId, String ownerUserId);

    @Query("DELETE FROM outbox")
    Completable clear();

    /** Drops queued sends that can never be accepted after membership loss. */
    @Query("DELETE FROM outbox WHERE conversationId = :conversationId "
            + "AND ownerUserId = :ownerUserId")
    Completable deleteForConversation(String conversationId, String ownerUserId);

    /** Synchronous variant for {@code AppDatabase.runInTransaction} bodies (issue #184). */
    @Query("DELETE FROM outbox WHERE conversationId = :conversationId "
            + "AND ownerUserId = :ownerUserId")
    void deleteForConversationInTransaction(String conversationId, String ownerUserId);
}
