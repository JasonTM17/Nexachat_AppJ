package com.nexachat.invite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupJoinRequestRepository extends JpaRepository<GroupJoinRequest, UUID> {

    Optional<GroupJoinRequest> findByConversation_IdAndUser_IdAndStatus(
            UUID conversationId, UUID userId, GroupJoinRequest.Status status);

    Optional<GroupJoinRequest> findFirstByConversation_IdAndUser_IdOrderByCreatedAtDesc(
            UUID conversationId, UUID userId);

    List<GroupJoinRequest> findAllByConversation_IdAndStatusOrderByCreatedAtDesc(
            UUID conversationId, GroupJoinRequest.Status status);

    List<GroupJoinRequest> findAllByConversation_IdOrderByCreatedAtDesc(UUID conversationId);

    long countByConversation_IdAndStatus(UUID conversationId, GroupJoinRequest.Status status);
}
