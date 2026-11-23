package com.nexachat.scheduled;

/** Result of one {@code sendAndMark} attempt on a claimed row. */
public enum DispatchOutcome {
    /** The message was sent (or replayed) and the row marked SENT. */
    SENT,
    /** The row was marked FAILED. */
    FAILED,
    /** A retryable failure released the claim; the row retries after backoff. */
    RETRY,
    /** The claim token no longer owned the row; nothing was done. */
    CLAIM_LOST
}
