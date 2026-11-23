package com.nexachat.scheduled;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable claim half of the scheduled-message dispatcher.
 *
 * <p>Claims are short database transactions: due rows are locked with
 * {@code FOR UPDATE SKIP LOCKED}, then marked with a lease token. The send
 * and mark steps run in their own per-row transactions afterwards, so no
 * claim-scan lock is ever held into a send (Wukong amendments 3 and 9). A
 * scheduler that lost its lease cannot move a row another scheduler now owns
 * because every guarded update re-checks the claim token.</p>
 */
@Service
public class ScheduledMessageClaimService {

    private final ScheduledMessageRepository scheduledMessageRepository;

    public ScheduledMessageClaimService(ScheduledMessageRepository scheduledMessageRepository) {
        this.scheduledMessageRepository = scheduledMessageRepository;
    }

    /**
     * Locks and claims up to {@code batchSize} due rows.
     *
     * @return the ids this run now owns
     */
    @Transactional
    public List<UUID> claimDue(long leaseSeconds, UUID claimToken, int batchSize) {
        List<UUID> due = scheduledMessageRepository.lockDueIds(leaseSeconds, batchSize);
        if (due.isEmpty()) {
            return List.of();
        }
        for (UUID id : due) {
            scheduledMessageRepository.markClaimed(id, claimToken);
        }
        return due;
    }

    /** Dispatch snapshot guarded by the lease token; empty when the claim was lost. */
    @Transactional(readOnly = true)
    public Optional<ScheduledMessageDispatch> findDispatch(UUID id, UUID claimToken) {
        return scheduledMessageRepository.findDispatch(id, claimToken);
    }
}
