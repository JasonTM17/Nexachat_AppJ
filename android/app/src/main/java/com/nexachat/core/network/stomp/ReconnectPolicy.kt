package com.nexachat.core.network.stomp

import javax.inject.Inject
import kotlin.random.Random

/**
 * Reconnect backoff calculator (plan §11.3.5): 1s, 2s, 4s, 8s, 16s capped,
 * ±20% jitter, at most [MAX_ATTEMPTS] consecutive attempts before the caller
 * switches to "offline mode" (manual re-arm via the next user action).
 *
 * Deliberately a pure, stateless value object — the reconnect LOOP is
 * caller-owned (RealtimeSession) so lifecycle, cancellation, and the resync
 * hook live in exactly one place.
 */
class ReconnectPolicy @Inject constructor() {

    /** Deterministic-jitter constructor for tests — Dagger never sees it. */
    internal constructor(jitterSource: () -> Double) : this() {
        this.jitterSource = jitterSource
    }

    private var jitterSource: () -> Double = { Random.nextDouble(-JITTER, JITTER) }

    /**
     * Delay before retry number [failedAttempts] (1-based): 1000ms, then
     * doubling up to a 16000ms cap, each ±[JITTER] jitter.
     */
    fun delayMillis(failedAttempts: Int): Long {
        val shift = (failedAttempts - 1).coerceIn(0, MAX_SHIFT)
        val base = BASE_MS shl shift
        val jittered = base * (1 + jitterSource())
        return jittered.toLong().coerceAtLeast(MIN_DELAY_MS)
    }

    /** Whether another attempt is allowed after [failedAttempts] failures. */
    fun shouldRetry(failedAttempts: Int): Boolean = failedAttempts < MAX_ATTEMPTS

    companion object {
        const val MAX_ATTEMPTS = 5
        const val BASE_MS = 1_000L
        const val MAX_DELAY_MS = 16_000L
        const val MIN_DELAY_MS = 100L
        private const val MAX_SHIFT = 4
        private const val JITTER = 0.2
    }
}
