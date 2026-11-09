package com.nexachat.invite;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
