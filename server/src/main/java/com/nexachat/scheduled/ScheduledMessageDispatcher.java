package com.nexachat.scheduled;

import com.nexachat.observability.ObservabilityMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Non-transactional orchestration around {@link ScheduledMessageDispatchService}.
 *
 * <p>A worker thread calls {@link #process} once per claimed row. The send
 * transaction either commits a SENT mark or throws; the throw is classified
 * here — only the allowlisted business codes become a terminal FAILED
 * (Wukong amendment 6), everything else releases the claim for an
 * exponential-backoff retry until the row crosses its 24h dead-letter
 * horizon. Both outcomes then commit in their own token-guarded transaction,
 * never in the rolled-back send transaction.</p>
 */
@Service
public class ScheduledMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMessageDispatcher.class);

    private final ScheduledMessageDispatchService dispatchService;
    private final ScheduledMessageRateLimiter rateLimiter;
    private final ObservabilityMetrics metrics;

    public ScheduledMessageDispatcher(ScheduledMessageDispatchService dispatchService,
                                      ScheduledMessageRateLimiter rateLimiter,
                                      ObservabilityMetrics metrics) {
        this.dispatchService = dispatchService;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    /**
     * Resolves one claimed row: sends it, fails it, or parks it for retry.
     * Never throws — a row this instance cannot move is reported as
     * {@link DispatchOutcome#CLAIM_LOST}.
     */
    public DispatchOutcome process(ScheduledMessageDispatch dispatch) {
        DispatchOutcome outcome;
        var consumption = rateLimiter.tryConsume(dispatch.senderId());
        if (!consumption.allowed()) {
            outcome = deferWhileThrottled(dispatch, consumption.retryAfterSeconds());
        } else {
            try {
                outcome = dispatchService.sendAndMark(dispatch);
            } catch (RuntimeException failure) {
                outcome = handleSendFailure(dispatch, failure);
            }
        }
        metrics.recordScheduledDispatch(outcome.name());
        return outcome;
    }

    /**
     * A sender whose scheduled-send bucket is empty keeps the row PENDING and
     * releases the claim until the bucket refills — smoothing a burst of due
     * rows without turning rate limiting into a terminal failure.
     */
    private DispatchOutcome deferWhileThrottled(ScheduledMessageDispatch dispatch,
                                                long retryAfterSeconds) {
        if (!dispatchService.releaseForRetry(dispatch, Math.max(1, retryAfterSeconds))) {
            return DispatchOutcome.CLAIM_LOST;
        }
        log.info("Scheduled message {} dispatch rate limited; retry in {}s",
                dispatch.id(), retryAfterSeconds);
        return DispatchOutcome.RETRY;
    }

    /**
     * Classifies a rolled-back send. Allowlisted business failures and rows
     * past the dead-letter horizon mark FAILED; anything else — suspended
     * sender, block refusals, data-access races, interrupts — releases the
     * claim so the next poll retries with exponential backoff.
     */
    private DispatchOutcome handleSendFailure(ScheduledMessageDispatch dispatch,
                                              RuntimeException failure) {
        String failureCode = ScheduledMessageFailure.failureCodeFor(failure);
        if (failureCode == null
                && ScheduledMessageFailure.isPastDeadLetter(dispatch.sendAt(), Instant.now())) {
            failureCode = ScheduledMessageFailure.RETRY_EXHAUSTED;
        }
        if (failureCode != null) {
            if (!dispatchService.markFailed(dispatch, failureCode)) {
                return DispatchOutcome.CLAIM_LOST;
            }
            log.warn("Scheduled message {} permanently failed ({}): {}",
                    dispatch.id(), failureCode, failure.toString());
            return DispatchOutcome.FAILED;
        }
        long delaySeconds = ScheduledMessageFailure.retryDelaySeconds(dispatch.attemptCount());
        if (!dispatchService.releaseForRetry(dispatch, delaySeconds)) {
            return DispatchOutcome.CLAIM_LOST;
        }
        log.warn("Scheduled message {} dispatch deferred ({}); retry in {}s",
                dispatch.id(), failure.getClass().getSimpleName(), delaySeconds);
        return DispatchOutcome.RETRY;
    }
}
