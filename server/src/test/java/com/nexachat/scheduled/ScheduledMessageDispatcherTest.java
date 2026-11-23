package com.nexachat.scheduled;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.observability.ObservabilityMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Failure classification of {@link ScheduledMessageDispatcher}: only the
 * allowlisted business codes mark a row FAILED; everything else releases the
 * claim for a backoff retry until the 24h dead-letter horizon.
 */
class ScheduledMessageDispatcherTest {

    private final ScheduledMessageDispatchService dispatchService =
            mock(ScheduledMessageDispatchService.class);
    private final ObservabilityMetrics metrics = mock(ObservabilityMetrics.class);
    private final ScheduledMessageRateLimiter rateLimiter =
            new ScheduledMessageRateLimiter(new RateLimitService(), 60);
    private final ScheduledMessageDispatcher dispatcher =
            new ScheduledMessageDispatcher(dispatchService, rateLimiter, metrics);

    @Test
    void aSuccessfulSendReportsSent() {
        ScheduledMessageDispatch dispatch = dispatch(Instant.now().minusSeconds(5), 0);
        when(dispatchService.sendAndMark(dispatch)).thenReturn(DispatchOutcome.SENT);

        assertThat(dispatcher.process(dispatch)).isEqualTo(DispatchOutcome.SENT);
        verify(dispatchService, never()).markFailed(eq(dispatch), org.mockito.ArgumentMatchers.anyString());
        verify(dispatchService, never()).releaseForRetry(eq(dispatch), anyLong());
        verify(metrics).recordScheduledDispatch("SENT");
    }

    @Test
    void aLostClaimIsReportedWithoutRetryWork() {
        ScheduledMessageDispatch dispatch = dispatch(Instant.now().minusSeconds(5), 0);
        when(dispatchService.sendAndMark(dispatch)).thenReturn(DispatchOutcome.CLAIM_LOST);

        assertThat(dispatcher.process(dispatch)).isEqualTo(DispatchOutcome.CLAIM_LOST);
        verify(dispatchService, never()).releaseForRetry(eq(dispatch), anyLong());
    }

    @Test
    void notMemberIsATerminalFailure() {
        ScheduledMessageDispatch dispatch = dispatch(Instant.now().minusSeconds(5), 0);
        when(dispatchService.sendAndMark(dispatch))
                .thenThrow(ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member"));
        when(dispatchService.markFailed(dispatch, "NOT_MEMBER")).thenReturn(true);

        assertThat(dispatcher.process(dispatch)).isEqualTo(DispatchOutcome.FAILED);
        verify(dispatchService).markFailed(dispatch, "NOT_MEMBER");
        verify(dispatchService, never()).releaseForRetry(eq(dispatch), anyLong());
        verify(metrics).recordScheduledDispatch("FAILED");
    }

    @Test
    void e2eeAndValidationFailuresAreTerminal() {
        ScheduledMessageDispatch dispatch = dispatch(Instant.now().minusSeconds(5), 0);
        doThrow(ApiException.of(ApiErrorCode.E2EE_REQUIRED, "E2EE"))
                .when(dispatchService).sendAndMark(dispatch);
        when(dispatchService.markFailed(dispatch, "E2EE_REQUIRED")).thenReturn(true);
        assertThat(dispatcher.process(dispatch)).isEqualTo(DispatchOutcome.FAILED);

        doThrow(ApiException.of(ApiErrorCode.VALIDATION_FAILED, "bad"))
                .when(dispatchService).sendAndMark(dispatch);
        when(dispatchService.markFailed(dispatch, "VALIDATION_FAILED")).thenReturn(true);
        assertThat(dispatcher.process(dispatch)).isEqualTo(DispatchOutcome.FAILED);
    }

    @Test
    void suspendedAndTransientFailuresReleaseTheClaimForRetry() {
        ScheduledMessageDispatch dispatch = dispatch(Instant.now().minusSeconds(5), 0);
        when(dispatchService.sendAndMark(dispatch))
                .thenThrow(ApiException.of(ApiErrorCode.ACCOUNT_SUSPENDED, "suspended"));
        when(dispatchService.releaseForRetry(eq(dispatch), anyLong())).thenReturn(true);

        assertThat(dispatcher.process(dispatch)).isEqualTo(DispatchOutcome.RETRY);
        ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(Long.class);
        verify(dispatchService).releaseForRetry(eq(dispatch), delay.capture());
        // attempt 0 -> 15s base backoff.
        assertThat(delay.getValue()).isEqualTo(15L);
        verify(dispatchService, never()).markFailed(eq(dispatch), org.mockito.ArgumentMatchers.anyString());
        verify(metrics).recordScheduledDispatch("RETRY");
    }

    @Test
    void blockedAndTechnicalFailuresAreAlsoRetryable() {
        ScheduledMessageDispatch dispatch = dispatch(Instant.now().minusSeconds(5), 3);
        doThrow(ApiException.of(ApiErrorCode.BLOCKED, "blocked"))
                .when(dispatchService).sendAndMark(dispatch);
        when(dispatchService.releaseForRetry(eq(dispatch), anyLong())).thenReturn(true);
        assertThat(dispatcher.process(dispatch)).isEqualTo(DispatchOutcome.RETRY);

        doThrow(new IllegalStateException("db gone"))
                .when(dispatchService).sendAndMark(dispatch);
        assertThat(dispatcher.process(dispatch)).isEqualTo(DispatchOutcome.RETRY);
    }

    @Test
    void retryDelayDoublesAndCapsAtTenMinutes() {
        assertThat(ScheduledMessageFailure.retryDelaySeconds(0)).isEqualTo(15L);
        assertThat(ScheduledMessageFailure.retryDelaySeconds(1)).isEqualTo(30L);
        assertThat(ScheduledMessageFailure.retryDelaySeconds(2)).isEqualTo(60L);
        assertThat(ScheduledMessageFailure.retryDelaySeconds(6)).isEqualTo(600L);
        assertThat(ScheduledMessageFailure.retryDelaySeconds(20)).isEqualTo(600L);
        assertThat(ScheduledMessageFailure.retryDelaySeconds(100)).isEqualTo(600L);
    }

    @Test
    void aRetryableFailurePastTheDeadLetterHorizonIsFailedInstead() {
        ScheduledMessageDispatch dispatch = dispatch(
                Instant.now().minus(ScheduledMessageFailure.DEAD_LETTER_AFTER).minusSeconds(60), 7);
        when(dispatchService.sendAndMark(dispatch))
                .thenThrow(ApiException.of(ApiErrorCode.ACCOUNT_SUSPENDED, "suspended"));
        when(dispatchService.markFailed(dispatch, "FAILED_RETRY_EXHAUSTED")).thenReturn(true);

        assertThat(dispatcher.process(dispatch)).isEqualTo(DispatchOutcome.FAILED);
        verify(dispatchService).markFailed(dispatch, "FAILED_RETRY_EXHAUSTED");
        verify(dispatchService, never()).releaseForRetry(eq(dispatch), anyLong());
    }

    @Test
    void aFailedMarkThatLostItsClaimIsNotRetriedByThisRun() {
        ScheduledMessageDispatch dispatch = dispatch(Instant.now().minusSeconds(5), 0);
        when(dispatchService.sendAndMark(dispatch))
                .thenThrow(ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member"));
        when(dispatchService.markFailed(dispatch, "NOT_MEMBER")).thenReturn(false);

        assertThat(dispatcher.process(dispatch)).isEqualTo(DispatchOutcome.CLAIM_LOST);
        verify(dispatchService, never()).releaseForRetry(eq(dispatch), anyLong());
    }

    @Test
    void aThrottledSenderReleasesTheClaimUntilTheBucketRefills() {
        ScheduledMessageRateLimiter onePerMinute =
                new ScheduledMessageRateLimiter(new RateLimitService(), 1);
        ScheduledMessageDispatcher limited =
                new ScheduledMessageDispatcher(dispatchService, onePerMinute, metrics);
        ScheduledMessageDispatch dispatch = dispatch(Instant.now().minusSeconds(5), 0);
        when(dispatchService.sendAndMark(dispatch)).thenReturn(DispatchOutcome.SENT);
        when(dispatchService.releaseForRetry(eq(dispatch), anyLong())).thenReturn(true);

        // First dispatch consumes the single token and reaches the send path.
        limited.process(dispatch);
        verify(dispatchService).sendAndMark(dispatch);

        // The second due row from the same sender is parked, not sent — the
        // bucket's own retry-after bounds the wait, never the exponential
        // backoff, and the send path is never entered.
        assertThat(limited.process(dispatch)).isEqualTo(DispatchOutcome.RETRY);
        ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(Long.class);
        verify(dispatchService).releaseForRetry(eq(dispatch), delay.capture());
        assertThat(delay.getValue()).isBetween(1L, 60L);
        verify(dispatchService, never()).markFailed(eq(dispatch),
                org.mockito.ArgumentMatchers.anyString());
        verify(dispatchService, times(1)).sendAndMark(dispatch);
        verify(metrics).recordScheduledDispatch("RETRY");
    }

    private static ScheduledMessageDispatch dispatch(Instant sendAt, int attemptCount) {
        return new ScheduledMessageDispatch(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "body", sendAt,
                attemptCount, UUID.randomUUID());
    }
}
