package com.nexachat.conversation;

import com.nexachat.conversation.dto.ConversationMemberView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, ConversationMemberId> {

    /**
     * Member read model for the conversation response: JOIN {@code users} once
     * and return the DTO projection (plan §9.3.7). Ordered by user id so the
     * array order is stable regardless of insert order.
     */
    @Query("""
            SELECT new com.nexachat.conversation.dto.ConversationMemberView(
                       u.id, u.displayName, u.phone)
            FROM ConversationMember m JOIN m.user u
            WHERE m.conversation.id = :conversationId
            ORDER BY u.id
            """)
    List<ConversationMemberView> findMemberViews(@Param("conversationId") UUID conversationId);
}
