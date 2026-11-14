package com.nexachat.invite;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupInviteLinkRepository extends JpaRepository<GroupInviteLink, UUID> {

    Optional<GroupInviteLink> findByTokenHash(String tokenHash);

    Optional<GroupInviteLink> findByConversation_IdAndRevokedAtIsNull(UUID conversationId);

    List<GroupInviteLink> findAllByConversation_IdAndRevokedAtIsNull(UUID conversationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM GroupInviteLink l WHERE l.id = :id")
    Optional<GroupInviteLink> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Atomically consumes one use of the link when it is active, unexpired, and not
     * exhausted. Returns the number of rows updated (0 or 1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE group_invite_links
               SET use_count = use_count + 1, updated_at = clock_timestamp()
             WHERE id = :id
               AND revoked_at IS NULL
               AND (expires_at IS NULL OR expires_at > clock_timestamp())
               AND (max_uses IS NULL OR use_count < max_uses)
            """, nativeQuery = true)
    int tryConsumeUse(@Param("id") UUID id);
}
