package com.nexachat.scheduled;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduledMessageRepository extends JpaRepository<ScheduledMessage, UUID> {

    /**
     * The POST idempotency lookup: {@code (sender_id, client_message_id)} is
     * unique, so a replay always finds the row the first request stored.
     */
    @Query("""
            SELECT s FROM ScheduledMessage s
            WHERE s.sender.id = :senderId AND s.clientMessageId = :clientMessageId
            """)
    Optional<ScheduledMessage> findBySenderAndClientMessageId(
            @Param("senderId") UUID senderId,
            @Param("clientMessageId") UUID clientMessageId);

    /** PENDING rows counted against the per-sender-per-conversation ceiling. */
    @Query("""
            SELECT COUNT(s) FROM ScheduledMessage s
            WHERE s.conversation.id = :conversationId
              AND s.sender.id = :senderId
              AND s.status = com.nexachat.scheduled.ScheduledMessageStatus.PENDING
            """)
    long countPending(@Param("conversationId") UUID conversationId,
                      @Param("senderId") UUID senderId);

    /**
     * The author's board for one conversation: every PENDING and FAILED row
     * plus SENT rows that completed recently enough to still matter to the
     * client list, oldest first.
     */
    @Query("""
            SELECT s FROM ScheduledMessage s
            WHERE s.conversation.id = :conversationId
              AND s.sender.id = :senderId
              AND (s.status IN (com.nexachat.scheduled.ScheduledMessageStatus.PENDING,
                                com.nexachat.scheduled.ScheduledMessageStatus.FAILED)
                   OR (s.status = com.nexachat.scheduled.ScheduledMessageStatus.SENT
                       AND s.updatedAt > :sentCutoff))
            ORDER BY s.sendAt ASC, s.id ASC
            """)
    List<ScheduledMessage> findListed(@Param("conversationId") UUID conversationId,
                                      @Param("senderId") UUID senderId,
                                      @Param("sentCutoff") Instant sentCutoff,
                                      Pageable pageable);

    /**
     * Row lock used by the dispatcher's send+mark step. The lock is taken only
     * after the sender's user key-share, keeping the user -> conversation ->
     * message parent order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ScheduledMessage s WHERE s.id = :id")
    Optional<ScheduledMessage> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Inserts a PENDING row, ignoring a duplicate idempotency key. The send
     * path's {@code INSERT ... ON CONFLICT DO NOTHING} twin: two racing POSTs
     * with the same {@code clientMessageId} resolve to exactly one row.
     *
     * @return 1 when the row was inserted, 0 on a dedup hit
     */
    @Modifying
    @Query(value = """
            INSERT INTO scheduled_messages
                (id, conversation_id, sender_id, client_message_id, body, send_at)
            VALUES (:id, :conversationId, :senderId, :clientMessageId, :body, :sendAt)
            ON CONFLICT (sender_id, client_message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicate(@Param("id") UUID id,
                                @Param("conversationId") UUID conversationId,
                                @Param("senderId") UUID senderId,
                                @Param("clientMessageId") UUID clientMessageId,
                                @Param("body") String body,
                                @Param("sendAt") Instant sendAt);

    /**
     * Locks the next batch of due rows. {@code SKIP LOCKED} makes two
     * schedulers claim disjoint batches instead of blocking or double-claiming;
     * a claim older than the lease cutoff is treated as abandoned and can be
     * picked up again after a crash. A retry-parked row only becomes due once
     * its {@code next_attempt_at} backoff has passed.
     */
    @Query(value = """
            SELECT id FROM scheduled_messages
            WHERE status = 'PENDING'
              AND send_at <= now()
              AND (next_attempt_at IS NULL OR next_attempt_at <= now())
              AND (claimed_at IS NULL
                   OR claimed_at < now()
                      - CAST(:leaseSeconds AS double precision) * interval '1 second')
            ORDER BY send_at ASC, id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockDueIds(@Param("leaseSeconds") long leaseSeconds,
                        @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ScheduledMessage s
            SET s.claimedAt = CURRENT_TIMESTAMP, s.claimToken = :claimToken,
                s.updatedAt = CURRENT_TIMESTAMP
            WHERE s.id = :id
              AND s.status = com.nexachat.scheduled.ScheduledMessageStatus.PENDING
            """)
    int markClaimed(@Param("id") UUID id,
                    @Param("claimToken") UUID claimToken);

    /** Dispatch snapshot guarded by the lease token (a re-claimed row is skipped). */
    @Query("""
            SELECT new com.nexachat.scheduled.ScheduledMessageDispatch(
                       s.id, s.conversation.id, s.sender.id, s.clientMessageId,
                       s.body, s.sendAt, s.attemptCount, s.claimToken)
            FROM ScheduledMessage s
            WHERE s.id = :id AND s.claimToken = :claimToken
              AND s.status = com.nexachat.scheduled.ScheduledMessageStatus.PENDING
            """)
    Optional<ScheduledMessageDispatch> findDispatch(@Param("id") UUID id,
                                                    @Param("claimToken") UUID claimToken);

    /**
     * Token-guarded terminal FAILED mark used when the send transaction
     * rolled back (an allowlisted business failure) — the mark runs in its
     * own transaction and only lands while this dispatcher still owns the
     * claim. The body is scrubbed with the terminal transition.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ScheduledMessage s
            SET s.status = com.nexachat.scheduled.ScheduledMessageStatus.FAILED,
                s.failureCode = :failureCode, s.body = '',
                s.claimedAt = NULL, s.claimToken = NULL, s.nextAttemptAt = NULL,
                s.updatedAt = CURRENT_TIMESTAMP
            WHERE s.id = :id AND s.claimToken = :claimToken
              AND s.status = com.nexachat.scheduled.ScheduledMessageStatus.PENDING
            """)
    int markFailedGuarded(@Param("id") UUID id,
                          @Param("claimToken") UUID claimToken,
                          @Param("failureCode") String failureCode);

    /**
     * Token-guarded claim release for a retryable failure: the claim pair is
     * cleared (so the author can still cancel), the attempt counter advances,
     * and the row is parked until {@code now() + delaySeconds}. All time math
     * runs against the database clock so app/database skew cannot retry early.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE scheduled_messages
            SET claimed_at = NULL, claim_token = NULL,
                attempt_count = attempt_count + 1,
                next_attempt_at = now()
                    + CAST(:delaySeconds AS double precision) * interval '1 second',
                updated_at = now()
            WHERE id = :id AND claim_token = :claimToken AND status = 'PENDING'
            """, nativeQuery = true)
    int releaseClaimForRetry(@Param("id") UUID id,
                             @Param("claimToken") UUID claimToken,
                             @Param("delaySeconds") long delaySeconds);

    /**
     * The cancel guard (Wukong amendment 2): a row can be canceled only while
     * it is unclaimed or its claim has already expired, so an in-flight
     * dispatch is never torn down underneath the sender.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE scheduled_messages
            SET status = 'CANCELED', failure_code = NULL, body = '',
                claimed_at = NULL, claim_token = NULL, next_attempt_at = NULL,
                updated_at = now()
            WHERE id = :id AND sender_id = :senderId AND conversation_id = :conversationId
              AND status = 'PENDING'
              AND (claimed_at IS NULL
                   OR claimed_at < now()
                      - CAST(:leaseSeconds AS double precision) * interval '1 second')
            """, nativeQuery = true)
    int cancelIfCancelable(@Param("id") UUID id,
                           @Param("senderId") UUID senderId,
                           @Param("conversationId") UUID conversationId,
                           @Param("leaseSeconds") long leaseSeconds);

    /**
     * Milliseconds until the earliest wake-up, calculated against PostgreSQL's
     * clock so app/database skew cannot cause an early poll. Unclaimed rows
     * wake at {@code send_at}, retry-parked rows at {@code next_attempt_at},
     * and claimed rows when their lease expires. All three ordered branches
     * use the scheduled-message indexes rather than sorting the full table.
     */
    @Query(value = """
            SELECT CEIL(EXTRACT(EPOCH FROM (MIN(wake_at) - statement_timestamp())) * 1000)::bigint
            FROM (
                (SELECT send_at AS wake_at
                 FROM scheduled_messages
                 WHERE status = 'PENDING' AND claimed_at IS NULL AND next_attempt_at IS NULL
                 ORDER BY send_at ASC, id ASC
                 LIMIT 1)
                UNION ALL
                (SELECT next_attempt_at AS wake_at
                 FROM scheduled_messages
                 WHERE status = 'PENDING' AND claimed_at IS NULL AND next_attempt_at IS NOT NULL
                 ORDER BY next_attempt_at ASC, id ASC
                 LIMIT 1)
                UNION ALL
                (SELECT claimed_at
                        + CAST(:leaseSeconds AS double precision) * interval '1 second' AS wake_at
                 FROM scheduled_messages
                 WHERE status = 'PENDING' AND claimed_at IS NOT NULL
                 ORDER BY claimed_at ASC, id ASC
                 LIMIT 1)
            ) AS scheduled_wake_candidates
            """, nativeQuery = true)
    Long findNextWakeDelayMillis(@Param("leaseSeconds") long leaseSeconds);
}
