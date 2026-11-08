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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    Completable insertAll(List<OfflineOutboxEntity> items);

    /**
     * Atomic claim query to transition claimable items (either SENDING or stale IN_PROGRESS)
     * to IN_PROGRESS and mark lastAttemptEpochMs with current timestamp.
     */
    @Query("UPDATE offline_outbox SET state = 'IN_PROGRESS', lastAttemptEpochMs = :now "
            + "WHERE state = 'SENDING' OR (state = 'IN_PROGRESS' AND lastAttemptEpochMs < :staleThreshold)")
    Single<Integer> claimLeases(long now, long staleThreshold);

    /**
     * Atomic claim query for a specific row by id.
     */
    @Query("UPDATE offline_outbox SET state = 'IN_PROGRESS', lastAttemptEpochMs = :now "
            + "WHERE id = :id AND (state = 'SENDING' OR (state = 'IN_PROGRESS' AND lastAttemptEpochMs < :staleThreshold))")
    Single<Integer> claimLease(String id, long now, long staleThreshold);

    @Query("SELECT * FROM offline_outbox WHERE state = 'SENDING' "
            + "OR (state = 'IN_PROGRESS' AND lastAttemptEpochMs < :staleThreshold) "
            + "ORDER BY createdAtEpochMs ASC")
    Single<List<OfflineOutboxEntity>> getClaimableItems(long staleThreshold);

    @Query("SELECT * FROM offline_outbox WHERE id = :id")
    Maybe<OfflineOutboxEntity> findById(String id);

    @Query("SELECT * FROM offline_outbox WHERE idempotencyKey = :idempotencyKey")
    Maybe<OfflineOutboxEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("UPDATE offline_outbox SET state = :state, retryCount = :retryCount, "
            + "lastAttemptEpochMs = :lastAttemptEpochMs WHERE id = :id")
    Completable updateState(String id, String state, int retryCount, long lastAttemptEpochMs);

    @Query("DELETE FROM offline_outbox WHERE id = :id")
    Completable deleteById(String id);

    @Query("DELETE FROM offline_outbox WHERE idempotencyKey = :idempotencyKey")
    Completable deleteByIdempotencyKey(String idempotencyKey);

    @Query("SELECT * FROM offline_outbox ORDER BY createdAtEpochMs ASC")
    Flowable<List<OfflineOutboxEntity>> getAllOrdered();

    @Query("SELECT * FROM offline_outbox ORDER BY createdAtEpochMs ASC")
    Single<List<OfflineOutboxEntity>> getAllOrderedList();

    @Query("SELECT COUNT(*) FROM offline_outbox WHERE state != 'FAILED'")
    Flowable<Integer> countActive();

    @Query("DELETE FROM offline_outbox")
    Completable clear();
}
