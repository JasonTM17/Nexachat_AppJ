package com.nexachat.scheduled;

import com.nexachat.auth.RateLimitService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Per-sender dispatch throttle for scheduled sends.
 *
 * <p>The HTTP message rate limit only guards REST/STOMP entry points — a
 * scheduled dispatch reaches {@code MessageService.send} internally, so a
 * burst of due rows from one account would bypass the normal smoothing
 * entirely. This limiter charges each send attempt against the sender's own
 * bucket; a denied attempt releases the claim for the bucket's
 * retry-after instead of hammering the send path.</p>
 *
 * <p>In-memory like every other bucket: per-node smoothing under the
 * documented single-node deployment.</p>
 */
@Service
public class ScheduledMessageRateLimiter {

    private final RateLimitService rateLimitService;
    private final long sendsPerMinute;

    public ScheduledMessageRateLimiter(
            RateLimitService rateLimitService,
            @Value("${nexachat.rate-limit.messages.scheduled-send-per-minute:60}")
            long sendsPerMinute) {
        this.rateLimitService = rateLimitService;
        this.sendsPerMinute = Math.max(1L, sendsPerMinute);
    }

    /**
     * Charges one send attempt against the sender's scheduled-send bucket.
     *
     * @return the shared consumption verdict; {@code retryAfterSeconds} bounds
     *         how long a denied row should wait before its next attempt
     */
    public RateLimitService.Consumption tryConsume(UUID senderId) {
        return rateLimitService.tryConsume(
                "scheduled-message-send|user:" + senderId,
                sendsPerMinute, Duration.ofMinutes(1));
    }
}
