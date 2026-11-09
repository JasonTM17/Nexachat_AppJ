package com.nexachat.poll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PollOptionRepository extends JpaRepository<PollOption, UUID> {

    List<PollOption> findByPoll_IdOrderByPositionAsc(UUID pollId);

    List<PollOption> findByPoll_IdInOrderByPoll_IdAscPositionAsc(Collection<UUID> pollIds);
}
