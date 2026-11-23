package com.nexachat.scheduled;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.scheduled.dto.CreateScheduledMessageRequest;
import com.nexachat.scheduled.dto.ScheduledMessageResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled-message endpoints, member-only and author-scoped:
 *
 * <ul>
 *   <li>{@code POST /} → 201 created / 200 replay of a still-PENDING row;
 *       409 {@code SCHEDULED_INVALID} echoing status when the key already
 *       reached a terminal state.</li>
 *   <li>{@code GET /} → 200 the caller's PENDING + FAILED + recent SENT rows,
 *       oldest first.</li>
 *   <li>{@code DELETE /{scheduledId}} → 204 canceled; 409 {@code DISPATCHING}
 *       while a claim is in flight or the row already terminated; 404 when
 *       the row is not the caller's.</li>
 * </ul>
 *
 * <p>Creation is rate-limited per user with the shared Bucket4j service, and
 * the endpoint lives under {@code /api/v1/conversations/**}, so the existing
 * global authentication and conversation interceptors still run.</p>
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/scheduled-messages")
public class ScheduledMessageController {

    private final ScheduledMessageService scheduledMessageService;
    private final RateLimitService rateLimitService;
    private final long createPerMinute;

    public ScheduledMessageController(ScheduledMessageService scheduledMessageService,
                                      RateLimitService rateLimitService,
                                      @Value("${nexachat.rate-limit.scheduled.create-per-minute:30}")
                                      long createPerMinute) {
        this.scheduledMessageService = scheduledMessageService;
        this.rateLimitService = rateLimitService;
        this.createPerMinute = createPerMinute;
    }

    @GetMapping
    public List<ScheduledMessageResponse> list(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        return scheduledMessageService.list(requireUserId(principal), conversationId);
    }

    @PostMapping
    public ResponseEntity<ScheduledMessageResponse> create(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody CreateScheduledMessageRequest request) {
        UUID userId = requireUserId(principal);
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                "scheduled-message-create|user:" + userId,
                createPerMinute,
                Duration.ofMinutes(1));
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED,
                    "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }

        ScheduledMessageCreateResult result =
                scheduledMessageService.create(userId, conversationId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.scheduledMessage());
    }

    @DeleteMapping("/{scheduledId}")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID scheduledId) {
        scheduledMessageService.cancel(requireUserId(principal), conversationId, scheduledId);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
