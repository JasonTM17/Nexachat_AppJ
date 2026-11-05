package com.nexachat.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PinnedMessageRepository extends JpaRepository<PinnedMessage, PinnedMessageId> {

    /**
     * Race-safe pin write: re-pinning an already-pinned (conversation,
     * message) pair conflicts on the composite PK and inserts nothing — the
     * same {@code ON CONFLICT DO NOTHING} discipline as the message-send path.
     *
     * @return 1 when the pin was created, 0 when it already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO pinned_messages (conversation_id, message_id, pinned_by)
            VALUES (:conversationId, :messageId, :pinnedBy)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringConflict(@Param("conversationId") UUID conversationId,
                               @Param("messageId") UUID messageId,
                               @Param("pinnedBy") UUID pinnedBy);

    /**
     * Unpin: removes exactly the (conversation, message) pin row.
     *
     * @return 1 when a pin was removed, 0 when the message was not pinned
     */
    @Modifying
    @Query("""
            DELETE FROM PinnedMessage p
            WHERE p.conversation.id = :conversationId AND p.message.id = :messageId
            """)
    int deleteOne(@Param("conversationId") UUID conversationId,
                  @Param("messageId") UUID messageId);

    /** {@code true} when the message is already pinned in this conversation. */
    boolean existsByConversation_IdAndMessage_Id(UUID conversationId, UUID messageId);

    /** Pin count for the ≤ 3 ceiling — read under the conversation row lock. */
    long countByConversation_Id(UUID conversationId);

    /** Pinned-list read for {@code GET /conversations/{id}/pins}, newest pin first. */
    List<PinnedMessage> findByConversation_IdOrderByPinnedAtDesc(UUID conversationId);
}
