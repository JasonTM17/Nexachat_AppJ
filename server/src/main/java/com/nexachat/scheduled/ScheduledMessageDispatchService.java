package com.nexachat.scheduled;

import com.nexachat.message.MessageService;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.observability.ObservabilityMetrics;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Per-row transactional half of the dispatcher (Wukong amendments 3 and 9).
 * Each method is one short database transaction on exactly one row; the
 * scheduler's claim transaction is a different, earlier transaction, so no
 * claim-scan lock is ever held while a send runs.
 *
 * <p>{@link #sendAndMark} is the atomic success path: it sends through the
 * ordinary {@link MessageService#send} (whose member/E2EE/suspended checks
 * are the dispatch-time re-check of amendment 1) and marks the row SENT in
 * the same commit, so a crash can never leave a sent message behind a still
 * PENDING row nor drop the send's broadcast. When the send throws, this
 * transaction rolls back wholesale — the failure is then classified and the
 * FAILED mark or retry release lands in its own transaction below.</p>
 */
@Service
public class ScheduledMessageDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMessageDispatchService.class);

    private final ScheduledMessageRepository scheduledMessageRepository;
    private final UserRepository userRepository;
    private final MessageService messageService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObservabilityMetrics metrics;

    public ScheduledMessageDispatchService(ScheduledMessageRepository scheduledMessageRepository,
                                           UserRepository userRepository,
                                           MessageService messageService,
                                           ApplicationEventPublisher eventPublisher,
                                           ObservabilityMetrics metrics) {
        this.scheduledMessageRepository = scheduledMessageRepository;
        this.userRepository = userRepository;
        this.messageService = messageService;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    /**
     * Sends the claimed row's body through the normal send path and marks it
     * SENT, all in one transaction.
     *
     * <p>Lock order: the sender's {@code FOR KEY SHARE} is taken before the
     * scheduled row lock, keeping the user -&gt; conversation -&gt; message
     * parent order — the account-deletion path holds the user lock while it
     * cancels PENDING rows, so taking the row lock first could deadlock.</p>
     *
     * <p>Two guards run before the send: the dead-letter horizon (a row
     * claimed more than 24h after {@code send_at} is failed rather than sent
     * absurdly late) and the deleted-sender check of amendment 1 (users are
     * tombstoned, so the sender FK never cascades).</p>
     *
     * @return {@link DispatchOutcome#SENT}, {@link DispatchOutcome#FAILED}
     *         (guard fired, marked in this same commit) or
     *         {@link DispatchOutcome#CLAIM_LOST}
     */
    @Transactional
    public DispatchOutcome sendAndMark(ScheduledMessageDispatch dispatch) {
        userRepository.lockForKeyShareSorted(List.of(dispatch.senderId()));
        ScheduledMessage row = scheduledMessageRepository.findByIdForUpdate(dispatch.id())
                .orElse(null);
        if (row == null
                || row.getStatus() != ScheduledMessageStatus.PENDING
                || !dispatch.claimToken().equals(row.getClaimToken())) {
            return DispatchOutcome.CLAIM_LOST;
        }
        Instant now = Instant.now();
        if (ScheduledMessageFailure.isPastDeadLetter(row.getSendAt(), now)) {
            failInTransaction(row, ScheduledMessageFailure.RETRY_EXHAUSTED, now);
            return DispatchOutcome.FAILED;
        }
        if (userRepository.findAuthValidityById(dispatch.senderId()).isEmpty()) {
            failInTransaction(row, ScheduledMessageFailure.ACCOUNT_DELETED, now);
            return DispatchOutcome.FAILED;
        }

        MessageService.SendResult result = messageService.send(
                dispatch.senderId(), dispatch.conversationId(),
                new SendMessageRequest(row.getClientMessageId(), "TEXT",
                        row.getBody(), null, null));
        row.markSent(result.message().id(), now);
        metrics.recordScheduledSendLatency(
                Duration.between(row.getSendAt(), now).toMillis());
        publishChanged(row);
        log.info("Scheduled message {} sent into conversation {} as message {}",
                row.getId(), dispatch.conversationId(), result.message().id());
        return DispatchOutcome.SENT;
    }

    /**
     * Token-guarded FAILED mark for a send that already rolled back (an
     * allowlisted business failure, or the dead-letter horizon reached on the
     * retry path). Publishes the author-facing change event only when the
     * update landed — a scheduler that lost its lease stays silent.
     *
     * @return true when this dispatcher still owned the claim and marked the row
     */
    @Transactional
    public boolean markFailed(ScheduledMessageDispatch dispatch, String failureCode) {
        if (scheduledMessageRepository.markFailedGuarded(
                dispatch.id(), dispatch.claimToken(), failureCode) != 1) {
            return false;
        }
        eventPublisher.publishEvent(new ScheduledMessageChangedEvent(
                dispatch.id(), dispatch.conversationId(), dispatch.senderId(),
                dispatch.clientMessageId(), ScheduledMessageStatus.FAILED.name(),
                failureCode, dispatch.sendAt(), null));
        log.warn("Scheduled message {} marked FAILED ({})", dispatch.id(), failureCode);
        return true;
    }

    /**
     * Token-guarded claim release for a retryable failure: the row goes back
     * to unclaimed PENDING (cancel stays possible) with the attempt counter
     * advanced and a {@code next_attempt_at} backoff.
     *
     * @return true when this dispatcher still owned the claim and released it
     */
    @Transactional
    public boolean releaseForRetry(ScheduledMessageDispatch dispatch, long delaySeconds) {
        return scheduledMessageRepository.releaseClaimForRetry(
                dispatch.id(), dispatch.claimToken(), delaySeconds) == 1;
    }

    private void failInTransaction(ScheduledMessage row, String failureCode, Instant now) {
        row.markFailed(failureCode, now);
        publishChanged(row);
        log.warn("Scheduled message {} marked FAILED ({})", row.getId(), failureCode);
    }

    private void publishChanged(ScheduledMessage row) {
        eventPublisher.publishEvent(new ScheduledMessageChangedEvent(
                row.getId(), row.getConversation().getId(), row.getSender().getId(),
                row.getClientMessageId(), row.getStatus().name(), row.getFailureCode(),
                row.getSendAt(), row.getSentMessageId()));
    }
}
