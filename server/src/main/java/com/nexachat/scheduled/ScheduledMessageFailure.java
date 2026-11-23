package com.nexachat.scheduled;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Values allowed in {@code scheduled_messages.failure_code}.
 *
 * <p>Only a terminal, non-retryable business outcome may mark a row FAILED —
 * the allowlist in {@link #FAILURE_CODES} (Wukong amendment 6). A suspended
 * account, a block edge or any technical/transient error releases the claim
 * for a backoff retry instead; the row is dead-lettered as
 * {@link #RETRY_EXHAUSTED} once {@code send_at + 24h} has passed.</p>
 */
public final class ScheduledMessageFailure {

    public static final String NOT_MEMBER = "NOT_MEMBER";
    public static final String E2EE_REQUIRED = "E2EE_REQUIRED";
    public static final String E2EE_NOT_ENABLED = "E2EE_NOT_ENABLED";
    public static final String E2EE_MEDIA_UNSUPPORTED = "E2EE_MEDIA_UNSUPPORTED";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String SCHEDULED_LIMIT = "SCHEDULED_LIMIT";
    public static final String ACCOUNT_DELETED = "ACCOUNT_DELETED";
    public static final String RETRY_EXHAUSTED = "FAILED_RETRY_EXHAUSTED";

    /** API error codes that map to a terminal FAILED dispatch, not a retry. */
    private static final Set<ApiErrorCode> FAILURE_CODES = Set.of(
            ApiErrorCode.NOT_MEMBER,
            ApiErrorCode.E2EE_REQUIRED,
            ApiErrorCode.E2EE_NOT_ENABLED,
            ApiErrorCode.E2EE_MEDIA_UNSUPPORTED,
            ApiErrorCode.VALIDATION_FAILED,
            ApiErrorCode.SCHEDULED_LIMIT);

    /**
     * A row still undelivered this long after {@code send_at} is dead-lettered
     * as {@link #RETRY_EXHAUSTED} instead of being released for another retry.
     */
    public static final Duration DEAD_LETTER_AFTER = Duration.ofHours(24);

    private static final long RETRY_BASE_SECONDS = 15L;
    private static final long RETRY_MAX_SECONDS = 600L;

    private ScheduledMessageFailure() {
    }

    /** Exponential backoff after the attempt that just failed: 15s, 30s, 60s ... capped at 10min. */
    public static long retryDelaySeconds(int attemptsAlreadyMade) {
        long shift = Math.min(Math.max(attemptsAlreadyMade, 0), 20);
        return Math.min(RETRY_BASE_SECONDS << (int) shift, RETRY_MAX_SECONDS);
    }

    /** True once the row is past its dead-letter horizon and must stop retrying. */
    public static boolean isPastDeadLetter(Instant sendAt, Instant now) {
        return !now.isBefore(sendAt.plus(DEAD_LETTER_AFTER));
    }

    /**
     * Maps an {@link ApiException} to its {@code failure_code} value, or
     * {@code null} when the error is retryable and must release the claim.
     */
    public static String failureCodeFor(RuntimeException failure) {
        if (failure instanceof ApiException api && FAILURE_CODES.contains(api.getCode())) {
            return api.getCode().getCode();
        }
        return null;
    }
}
