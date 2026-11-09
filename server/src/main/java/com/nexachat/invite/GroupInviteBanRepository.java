package com.nexachat.invite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface GroupInviteBanRepository extends JpaRepository<GroupInviteBan, UUID> {

    @Query("""
            SELECT CASE WHEN COUNT(b) > 0 THEN TRUE ELSE FALSE END
            FROM GroupInviteBan b
            WHERE b.conversation.id = :conversationId
              AND b.user.id = :userId
              AND (b.inviteLink IS NULL OR b.inviteLink.id = :inviteLinkId)
            """)
    boolean isBannedFromLink(@Param("conversationId") UUID conversationId,
                             @Param("userId") UUID userId,
                             @Param("inviteLinkId") UUID inviteLinkId);

    @Modifying
    @Query("""
            DELETE FROM GroupInviteBan b
            WHERE b.conversation.id = :conversationId
              AND b.user.id = :userId
            """)
    void deleteAllByConversationIdAndUserId(@Param("conversationId") UUID conversationId,
                                            @Param("userId") UUID userId);
}
