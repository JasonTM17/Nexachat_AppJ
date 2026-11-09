package com.nexachat.reminder;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable claim/advance half of the reminder scheduler.
 *
 * <p>Claims are short database transactions: due rows are locked with
 * {@code FOR UPDATE SKIP LOCKED}, then marked with a lease token. Delivery
 * happens outside the claim transaction, and the advance step re-checks the
 * lease token under a row lock, so a scheduler that lost its lease cannot move
 * a reminder that another scheduler now owns.</p>
 */
@Service
public class ReminderClaimService {

    private final ConversationReminderRepository reminderRepository;

    public ReminderClaimService(ConversationReminderRepository reminderRepository) {
        this.reminderRepository = reminderRepository;
    }

    /**
     * Locks and claims up to {@code batchSize} due reminders.
     *
     * @return the ids this run now owns
     */
    @Transactional
    public List<UUID> claimDue(long leaseSeconds, UUID claimToken, int batchSize) {
        List<UUID> due = reminderRepository.lockDueIds(leaseSeconds, batchSize);
        if (due.isEmpty()) {
            return List.of();
        }
        for (UUID id : due) {
            reminderRepository.markClaimed(id, claimToken);
        }
        return due;
    }

    /** Snapshot guarded by the lease token; empty when the claim was lost. */
    @Transactional(readOnly = true)
    public Optional<ReminderDelivery> findDelivery(UUID reminderId, UUID claimToken) {
        return reminderRepository.findDelivery(reminderId, claimToken);
    }

    /**
     * Sends the reminder to its next state: one-off rows become fired,
     * repeating rows move to the first future occurrence. Returns false when
     * the claim token no longer matches (another scheduler owns the row).
     */
    @Transactional
    public boolean advance(UUID reminderId, UUID claimToken) {
        ConversationReminder reminder = reminderRepository.findByIdForUpdate(reminderId)
                .orElse(null);
        if (reminder == null
                || reminder.getFiredAt() != null
                || !claimToken.equals(reminder.getClaimToken())) {
            return false;
        }
        Instant firedAt = Instant.now();
        Optional<Instant> next = reminder.getRepeatRule()
                .nextAfter(reminder.getAnchorAt(), firedAt);
        if (next.isPresent()) {
            reminder.reschedule(next.get(), firedAt);
        } else {
            reminder.complete(firedAt);
        }
        return true;
    }

    /** Deletes completed one-off rows so the table cannot grow without bound. */
    @Transactional
    public int purgeFired(int retentionDays) {
        Instant cutoff = Instant.now().minus(Math.max(1, retentionDays), ChronoUnit.DAYS);
        return reminderRepository.deleteFiredBefore(cutoff);
    }
}
