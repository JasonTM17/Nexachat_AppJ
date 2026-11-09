package com.nexachat.poll;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PollRepository extends JpaRepository<Poll, UUID> {

    /**
     * Serializes every write for one poll: vote replacement, close and delete
     * all take this lock before reading the current selection. Single-choice
     * uniqueness spans vote rows, so the lock is the atomicity boundary; the
     * composite vote primary key is the second guard.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Poll p WHERE p.id = :id")
    Optional<Poll> findByIdForUpdate(@Param("id") UUID id);

    Optional<Poll> findByIdAndConversation_Id(UUID id, UUID conversationId);

    /** Next revision for the change feed (one value per visible mutation). */
    @Query(value = "SELECT nextval('poll_version_seq')", nativeQuery = true)
    Long nextVersion();

    /** First newest-first page; no cursor means no nullable timestamp bind. */
    @Query("""
            SELECT p FROM Poll p
            WHERE p.conversation.id = :conversationId
              AND p.deletedAt IS NULL
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Poll> findVisibleFirstPage(@Param("conversationId") UUID conversationId,
                                    Pageable pageable);

    /**
     * Newest-first page after a cursor. Both cursor fields are non-null; the
     * previous page's last poll supplies them.
     */
    @Query("""
            SELECT p FROM Poll p
            WHERE p.conversation.id = :conversationId
              AND p.deletedAt IS NULL
              AND (p.createdAt < :beforeCreatedAt
                   OR (p.createdAt = :beforeCreatedAt AND p.id < :beforeId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Poll> findVisiblePageBefore(@Param("conversationId") UUID conversationId,
                                     @Param("beforeCreatedAt") Instant beforeCreatedAt,
                                     @Param("beforeId") UUID beforeId,
                                     Pageable pageable);

    /** Change feed: every revision after {@code since}, oldest first, tombstones included. */
    @Query("""
            SELECT p FROM Poll p
            WHERE p.conversation.id = :conversationId
              AND p.version > :since
            ORDER BY p.version ASC
            """)
    List<Poll> findChanges(@Param("conversationId") UUID conversationId,
                           @Param("since") long since,
                           Pageable pageable);
}
