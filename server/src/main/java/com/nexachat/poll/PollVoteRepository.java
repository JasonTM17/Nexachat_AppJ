package com.nexachat.poll;

import com.nexachat.poll.dto.PollVoteRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Vote writes are deliberately statement-based: {@code delete + insert} runs
 * under the poll row lock, and {@code ON CONFLICT DO NOTHING} makes the
 * composite key the final guard if a concurrent writer ever slips past it.
 */
public interface PollVoteRepository extends JpaRepository<PollVote, PollVoteId> {

    @Query("""
            SELECT new com.nexachat.poll.dto.PollVoteRow(v.poll.id, v.option.id, v.user.id)
            FROM PollVote v
            WHERE v.poll.id IN :pollIds
            """)
    List<PollVoteRow> findRowsByPollIds(@Param("pollIds") Collection<UUID> pollIds);

    @Query("""
            SELECT v.option.id FROM PollVote v
            WHERE v.poll.id = :pollId AND v.user.id = :userId
            ORDER BY v.option.position
            """)
    List<UUID> findOptionIdsByPollAndUser(@Param("pollId") UUID pollId,
                                          @Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM PollVote v WHERE v.poll.id = :pollId AND v.user.id = :userId")
    int deleteByPollAndUser(@Param("pollId") UUID pollId, @Param("userId") UUID userId);

    @Modifying
    @Query(value = """
            INSERT INTO poll_votes (poll_id, option_id, user_id)
            VALUES (:pollId, :optionId, :userId)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertVote(@Param("pollId") UUID pollId,
                   @Param("optionId") UUID optionId,
                   @Param("userId") UUID userId);
}
