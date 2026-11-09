package com.nexachat.reminder;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationReminderRepository extends JpaRepository<ConversationReminder, UUID> {

    /**
     * The upcoming board: active reminders with a future occurrence, oldest
     * first. Creator names are fetched in the same query (no N+1).
     */
    @Query("""
            SELECT r FROM ConversationReminder r JOIN FETCH r.createdBy
            WHERE r.conversation.id = :conversationId
              AND r.firedAt IS NULL
              AND r.remindAt > :now
            ORDER BY r.remindAt ASC, r.id ASC
            """)
    List<ConversationReminder> findUpcoming(@Param("conversationId") UUID conversationId,
                                            @Param("now") Instant now);

    /** Active rows counted against the 50-reminder ceiling. */
    @Query("""
            SELECT COUNT(r) FROM ConversationReminder r
            WHERE r.conversation.id = :conversationId
              AND r.firedAt IS NULL
            """)
    long countActive(@Param("conversationId") UUID conversationId);

    /**
     * Row lock used by edit/delete and by the scheduler's advance step. Taking
     * the reminder row lock (never the conversation lock) keeps the scheduler
     * from deadlocking with CRUD.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ConversationReminder r WHERE r.id = :id")
    Optional<ConversationReminder> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Locks the next batch of due rows. {@code SKIP LOCKED} makes two
     * schedulers claim disjoint batches instead of blocking or double-claiming;
     * a claim older than the lease cutoff is treated as abandoned and can be
     * picked up again after a crash.
     */
    @Query(value = """
            SELECT id FROM conversation_reminders
            WHERE fired_at IS NULL
              AND remind_at <= now()
              AND (claimed_at IS NULL
                   OR claimed_at < now()
                      - CAST(:leaseSeconds AS double precision) * interval '1 second')
            ORDER BY remind_at ASC, id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockDueIds(@Param("leaseSeconds") long leaseSeconds,
                          @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ConversationReminder r
            SET r.claimedAt = CURRENT_TIMESTAMP, r.claimToken = :claimToken,
                r.updatedAt = CURRENT_TIMESTAMP
            WHERE r.id = :id AND r.firedAt IS NULL
            """)
    int markClaimed(@Param("id") UUID id,
                    @Param("claimToken") UUID claimToken);

    /** Snapshot read guarded by the lease token (a re-claimed row is skipped). */
    @Query("""
            SELECT new com.nexachat.reminder.ReminderDelivery(
                       r.id, r.conversation.id, r.title, r.remindAt, r.anchorAt,
                       r.repeatRule, r.claimToken)
            FROM ConversationReminder r
            WHERE r.id = :id AND r.claimToken = :claimToken AND r.firedAt IS NULL
            """)
    Optional<ReminderDelivery> findDelivery(@Param("id") UUID id,
                                            @Param("claimToken") UUID claimToken);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM ConversationReminder r
            WHERE r.firedAt IS NOT NULL AND r.firedAt < :cutoff
            """)
    int deleteFiredBefore(@Param("cutoff") Instant cutoff);
}
