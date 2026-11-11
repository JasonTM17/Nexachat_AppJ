package com.nexachat.call;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CallRecordRepository extends JpaRepository<CallRecord, UUID> {

    @Query("SELECT c.callId FROM CallRecord c WHERE c.callerId = :userId OR c.calleeId = :userId")
    List<UUID> findCallIdsByParticipantId(@Param("userId") UUID userId);

    @Query("""
            SELECT c FROM CallRecord c
            WHERE c.conversationId = :conversationId
            ORDER BY c.startedAt DESC, c.callId DESC
            """)
    List<CallRecord> findConversationPage(
            @Param("conversationId") UUID conversationId,
            Pageable pageable);

    @Query("""
            SELECT c FROM CallRecord c
            WHERE c.conversationId = :conversationId
              AND (c.startedAt < :cursorStartedAt
                   OR (c.startedAt = :cursorStartedAt AND c.callId < :cursorCallId))
            ORDER BY c.startedAt DESC, c.callId DESC
            """)
    List<CallRecord> findConversationPageAfter(
            @Param("conversationId") UUID conversationId,
            @Param("cursorStartedAt") Instant cursorStartedAt,
            @Param("cursorCallId") UUID cursorCallId,
            Pageable pageable);
}
