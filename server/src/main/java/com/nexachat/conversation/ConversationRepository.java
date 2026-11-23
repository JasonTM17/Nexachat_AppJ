package com.nexachat.conversation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Canonical DIRECT pair-key lookup — the idempotency anchor of open-direct. */
    Optional<Conversation> findByDirectKey(String directKey);

    /** Bounded UUID keyset page used by opt-in Firebase projection backfill. */
    @Query("SELECT c.id FROM Conversation c WHERE (:afterId IS NULL OR c.id > :afterId) ORDER BY c.id")
    List<UUID> findIdsAfter(@Param("afterId") UUID afterId, Pageable pageable);

    /** Bounded keyset page of direct conversations for reader-edge backfill. */
    @Query("SELECT c.id FROM Conversation c WHERE c.type = :type "
            + "AND (:afterId IS NULL OR c.id > :afterId) ORDER BY c.id")
    List<UUID> findIdsAfterByType(@Param("afterId") UUID afterId,
                                  @Param("type") Conversation.Type type,
                                  Pageable pageable);

    /**
     * The immutable {@code type} discriminator, read without a row lock —
     * the DDL CHECK ties it to {@code direct_key}, so a plain SELECT cannot
     * disagree with the row {@link #findByIdForUpdate} locks right after.
     */
    @Query("SELECT c.type FROM Conversation c WHERE c.id = :id")
    Optional<Conversation.Type> findTypeById(@Param("id") UUID id);

    /**
     * {@code SELECT ... FOR UPDATE} on the conversation row. Writers retain
     * this lock through commit when they need per-conversation serialization.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conversation c WHERE c.id = :id")
    Optional<Conversation> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Increment the high-water revision and return it in one round-trip;
     * callers hold {@code findByIdForUpdate} until commit.
     */
    @Query(value = "UPDATE conversations SET change_version = change_version + 1 WHERE id = :id RETURNING change_version",
            nativeQuery = true)
    Long incrementAndGetChangeVersion(@Param("id") UUID id);

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
