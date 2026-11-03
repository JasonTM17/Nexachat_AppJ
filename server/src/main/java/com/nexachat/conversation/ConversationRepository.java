package com.nexachat.conversation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Canonical DIRECT pair-key lookup — the idempotency anchor of open-direct. */
    Optional<Conversation> findByDirectKey(String directKey);

    /**
     * {@code SELECT ... FOR UPDATE} on the conversation row — serializes
     * pin/unpin for the ≤ 3 ceiling (phase-10): a racing {@code pin()} waits
     * on this lock, then re-reads the count after the winner commits, so two
     * concurrent pins cannot both pass the limit check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conversation c WHERE c.id = :id")
    Optional<Conversation> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Denormalized inbox pointers (plan §9.3.5), advanced inside the send
     * transaction. Monotonic guard: two racing sends can commit out of seq
     * order, and the loser must not move the pointers backwards.
     */
    @Modifying
    @Query("""
            UPDATE Conversation c
            SET c.lastMessageId = :messageId, c.lastMessageSeq = :seq
            WHERE c.id = :conversationId
              AND (c.lastMessageSeq IS NULL OR c.lastMessageSeq < :seq)
            """)
    int advanceLastMessage(@Param("conversationId") UUID conversationId,
                           @Param("messageId") UUID messageId,
                           @Param("seq") long seq);
}
