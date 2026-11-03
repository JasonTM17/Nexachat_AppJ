package com.nexachat.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Canonical DIRECT pair-key lookup — the idempotency anchor of open-direct. */
    Optional<Conversation> findByDirectKey(String directKey);

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
