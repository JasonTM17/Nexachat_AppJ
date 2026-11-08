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

/**
 * Access to the persistent offline outbox queue with atomic lease locking.
 */
@Dao
public interface OfflineOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable insert(OfflineOutboxEntity item);

    /** Synchronous row operations used only inside AppDatabase.runInTransaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertBlocking(OfflineOutboxEntity item);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable insertAll(List<OfflineOutboxEntity> items);

    /**
     * Atomic claim query to transition claimable items (either PENDING/SENDING or stale IN_PROGRESS)
     * to IN_PROGRESS and mark lastAttemptEpochMs with current timestamp.
     */
    @Query("UPDATE offline_outbox SET state = 'IN_PROGRESS', lastAttemptEpochMs = :now "
            + "WHERE state IN ('PENDING', 'SENDING') OR (state = 'IN_PROGRESS' AND lastAttemptEpochMs < :staleThreshold)")
    Single<Integer> claimLeases(long now, long staleThreshold);

    /**
     * Atomic claim query for a specific row by id.
     */
    @Query("UPDATE offline_outbox SET state = 'IN_PROGRESS', lastAttemptEpochMs = :now "
            + "WHERE id = :id AND (state IN ('PENDING', 'SENDING') OR (state = 'IN_PROGRESS' AND lastAttemptEpochMs < :staleThreshold))")
    Single<Integer> claimLease(String id, long now, long staleThreshold);

    @Query("SELECT * FROM offline_outbox WHERE state IN ('PENDING', 'SENDING') "
            + "OR (state = 'IN_PROGRESS' AND lastAttemptEpochMs < :staleThreshold) "
            + "ORDER BY createdAtEpochMs ASC")
    Single<List<OfflineOutboxEntity>> getClaimableItems(long staleThreshold);

    @Query("SELECT * FROM offline_outbox ORDER BY createdAtEpochMs ASC")
    List<OfflineOutboxEntity> getAllOrderedBlocking();

    @Query("SELECT * FROM offline_outbox WHERE id = :id")
    OfflineOutboxEntity findByIdBlocking(String id);

    @Query("SELECT * FROM offline_outbox WHERE id = :id")
    Maybe<OfflineOutboxEntity> findById(String id);

    @Query("SELECT * FROM offline_outbox WHERE idempotencyKey = :idempotencyKey")
    Maybe<OfflineOutboxEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("UPDATE offline_outbox SET state = :state, retryCount = :retryCount, "
            + "lastAttemptEpochMs = :lastAttemptEpochMs WHERE id = :id")
    Completable updateState(String id, String state, int retryCount, long lastAttemptEpochMs);

    @Query("UPDATE offline_outbox SET payloadJson = :payloadJson WHERE id = :id "
            + "AND state IN ('PENDING', 'SENDING', 'IN_PROGRESS')")
    int updatePayloadBlocking(String id, String payloadJson);

    @Query("UPDATE offline_outbox SET state = 'SENT', lastAttemptEpochMs = :now WHERE id = :id")
    Completable markSent(String id, long now);

    @Query("UPDATE offline_outbox SET state = 'SENT', lastAttemptEpochMs = :now "
            + "WHERE id = :id AND state = 'IN_PROGRESS'")
    int markSentBlocking(String id, long now);

    @Query("UPDATE offline_outbox SET state = 'FAILED', lastAttemptEpochMs = :now WHERE id = :id")
    Completable markFailed(String id, long now);

    @Query("UPDATE offline_outbox SET state = 'FAILED', lastAttemptEpochMs = :now "
            + "WHERE id = :id AND state = 'IN_PROGRESS'")
    int markFailedBlocking(String id, long now);

    @Query("UPDATE offline_outbox SET state = 'PENDING', retryCount = :retryCount, "
            + "payloadJson = :payloadJson, lastAttemptEpochMs = 0 "
            + "WHERE id = :id AND state = 'IN_PROGRESS'")
    Completable releaseLease(String id, int retryCount, String payloadJson);

    @Query("DELETE FROM offline_outbox WHERE id = :id")
    Completable deleteById(String id);

    @Query("DELETE FROM offline_outbox WHERE id = :id")
    int deleteByIdBlocking(String id);

    @Query("DELETE FROM offline_outbox WHERE idempotencyKey = :idempotencyKey")
    Completable deleteByIdempotencyKey(String idempotencyKey);

    @Query("DELETE FROM offline_outbox")
    void clearBlocking();

    @Query("SELECT * FROM message_reactions WHERE messageId = :messageId")
    List<MessageReactionEntity> getReactionsBlocking(String messageId);

    @Query("DELETE FROM message_reactions WHERE messageId = :messageId")
    void deleteReactionsBlocking(String messageId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertReactionsBlocking(List<MessageReactionEntity> reactions);

    @Query("SELECT * FROM pinned_messages WHERE conversationId = :conversationId "
            + "AND messageId = :messageId")
    PinnedMessageEntity findPinBlocking(String conversationId, String messageId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPinBlocking(PinnedMessageEntity pin);

    @Query("DELETE FROM pinned_messages WHERE conversationId = :conversationId "
            + "AND messageId = :messageId")
    void deletePinBlocking(String conversationId, String messageId);

    @Query("SELECT * FROM offline_outbox ORDER BY createdAtEpochMs ASC")
    Flowable<List<OfflineOutboxEntity>> getAllOrdered();

    @Query("SELECT * FROM offline_outbox ORDER BY createdAtEpochMs ASC")
    Single<List<OfflineOutboxEntity>> getAllOrderedList();

    @Query("SELECT COUNT(*) FROM offline_outbox WHERE state NOT IN ('FAILED', 'SENT')")
    Flowable<Integer> countActive();

    @Query("DELETE FROM offline_outbox")
    Completable clear();
}
