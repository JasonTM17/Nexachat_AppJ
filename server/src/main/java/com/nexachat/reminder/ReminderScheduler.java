package com.nexachat.reminder;

import com.nexachat.conversation.ConversationMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Polling scheduler for due conversation reminders.
 *
 * <p>State lives entirely in {@code conversation_reminders}; the in-memory
 * poll loop is disposable. After a restart (or when two instances run) the
 * database claim rules decide who fires each occurrence:</p>
 *
 * <ol>
 *   <li>claim due rows with {@code FOR UPDATE SKIP LOCKED} plus a lease token,</li>
 *   <li>snapshot the row while the lease is valid,</li>
 *   <li>push to the current members,</li>
 *   <li>advance or complete under the reminder row lock.</li>
 * </ol>
 *
 * <p>The lease makes a crash after claim recoverable: the row becomes due
 * again when the lease expires. The trade-off is at-least-once delivery for
 * the narrow window between FCM accepting a push and the advance commit; a
 * reminder is never lost because of that window.</p>
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderClaimService claimService;
    private final ConversationMemberRepository memberRepository;
    private final ReminderPushSender pushSender;
    private final int batchSize;
    private final long claimLeaseSeconds;
    private final boolean enabled;
    private final int retentionDays;

    public ReminderScheduler(ReminderClaimService claimService,
                             ConversationMemberRepository memberRepository,
                             ReminderPushSender pushSender,
                             @Value("${nexachat.reminders.batch-size:10}") int batchSize,
                             @Value("${nexachat.reminders.claim-lease-seconds:600}") long claimLeaseSeconds,
                             @Value("${nexachat.reminders.enabled:true}") boolean enabled,
                             @Value("${nexachat.reminders.retention-days:30}") int retentionDays) {
        this.claimService = claimService;
        this.memberRepository = memberRepository;
        this.pushSender = pushSender;
        this.batchSize = Math.max(1, batchSize);
        this.claimLeaseSeconds = Math.max(1L, claimLeaseSeconds);
        this.enabled = enabled;
        this.retentionDays = Math.max(1, retentionDays);
    }

    /**
     * One poll cycle. Public so an integration test can drive a deterministic
     * "restart" without waiting for the timer.
     *
     * @return number of reminders whose state advanced
     */
    public int runOnce() {
        UUID claimToken = UUID.randomUUID();

        List<UUID> claimed = claimService.claimDue(claimLeaseSeconds, claimToken, batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }

        int advanced = 0;
        for (UUID reminderId : claimed) {
            try {
                Optional<ReminderDelivery> snapshot = claimService.findDelivery(reminderId, claimToken);
                if (snapshot.isEmpty()) {
                    continue;
                }
                ReminderDelivery delivery = snapshot.get();
                List<UUID> memberIds = memberRepository.findMemberUserIds(delivery.conversationId());
                pushSender.send(delivery, memberIds);
                if (claimService.advance(reminderId, claimToken)) {
                    advanced++;
                }
            } catch (RuntimeException failure) {
                // The claim is left in place; it becomes due again when the
                // lease expires, so a transient database or push failure does
                // not lose the reminder.
                log.warn("Reminder {} dispatch failed ({}); it will be retried after the claim lease",
                        reminderId, failure.getClass().getSimpleName());
            }
        }
        if (advanced > 0) {
            log.info("Advanced {} due reminder(s)", advanced);
        }
        return advanced;
    }

    @Scheduled(fixedDelayString = "${nexachat.reminders.poll-interval-ms:1000}",
            initialDelayString = "${nexachat.reminders.initial-delay-ms:5000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.warn("Reminder poll failed ({})", failure.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${nexachat.reminders.retention-sweep-interval-ms:3600000}",
            initialDelayString = "${nexachat.reminders.retention-initial-delay-ms:60000}")
    public void purgeCompleted() {
        if (!enabled) {
            return;
        }
        try {
            int purged = claimService.purgeFired(retentionDays);
            if (purged > 0) {
                log.info("Purged {} completed reminder(s)", purged);
            }
        } catch (RuntimeException failure) {
            log.warn("Reminder retention sweep failed ({})", failure.getClass().getSimpleName());
        }
    }
}
