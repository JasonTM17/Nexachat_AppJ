package com.nexachat.invite;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupJoinRequestRepository extends JpaRepository<GroupJoinRequest, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM GroupJoinRequest r WHERE r.id = :id")
    Optional<GroupJoinRequest> findByIdForUpdate(@Param("id") UUID id);

    Optional<GroupJoinRequest> findByConversation_IdAndUser_IdAndStatus(
            UUID conversationId, UUID userId, GroupJoinRequest.Status status);

    Optional<GroupJoinRequest> findFirstByConversation_IdAndUser_IdOrderByCreatedAtDesc(
            UUID conversationId, UUID userId);

    List<GroupJoinRequest> findAllByConversation_IdAndStatusOrderByCreatedAtDesc(
            UUID conversationId, GroupJoinRequest.Status status);

    List<GroupJoinRequest> findAllByConversation_IdOrderByCreatedAtDesc(UUID conversationId);

    @EntityGraph(attributePaths = {"conversation", "user", "reviewedBy"})
    List<GroupJoinRequest> findByConversation_IdAndStatusOrderByCreatedAtDescIdDesc(
            UUID conversationId, GroupJoinRequest.Status status, Pageable pageable);

    @EntityGraph(attributePaths = {"conversation", "user", "reviewedBy"})
    List<GroupJoinRequest> findByConversation_IdOrderByCreatedAtDescIdDesc(
            UUID conversationId, Pageable pageable);

    long countByConversation_IdAndStatus(UUID conversationId, GroupJoinRequest.Status status);
}
