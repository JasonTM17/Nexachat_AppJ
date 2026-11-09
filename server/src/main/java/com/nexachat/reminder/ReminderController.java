package com.nexachat.reminder;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.reminder.dto.CreateReminderRequest;
import com.nexachat.reminder.dto.ReminderResponse;
import com.nexachat.reminder.dto.UpdateReminderRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reminder board endpoints, member-only:
 *
 * <ul>
 *   <li>{@code GET /} → 200 upcoming reminders, oldest first; any member.</li>
 *   <li>{@code POST /} → 201 created / 200 idempotent replay; any member;
 *       optional {@code Idempotency-Key} (UUID).</li>
 *   <li>{@code PATCH /{reminderId}} → 200; creator or GROUP admin.</li>
 *   <li>{@code DELETE /{reminderId}} → 204; creator or GROUP admin.</li>
 * </ul>
 *
 * <p>Creation is rate-limited per user with the shared Bucket4j service. The
 * endpoint lives under {@code /api/v1/conversations/**}, so the existing global
 * authentication and conversation interceptors still run.</p>
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/reminders")
public class ReminderController {

    private final ReminderService reminderService;
    private final RateLimitService rateLimitService;
    private final long createPerMinute;

    public ReminderController(ReminderService reminderService,
                              RateLimitService rateLimitService,
                              @Value("${nexachat.rate-limit.reminders.create-per-minute:30}")
                              long createPerMinute) {
        this.reminderService = reminderService;
        this.rateLimitService = rateLimitService;
        this.createPerMinute = createPerMinute;
    }

    @GetMapping
    public List<ReminderResponse> list(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        return reminderService.list(requireUserId(principal), conversationId);
    }

    @PostMapping
    public ResponseEntity<ReminderResponse> create(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody CreateReminderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID userId = requireUserId(principal);
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                "reminder-create|user:" + userId,
                createPerMinute,
                Duration.ofMinutes(1));
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED,
                    "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }

        ReminderCreateResult result = reminderService.create(
                userId, conversationId, request, idempotencyKey);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.reminder());
    }

    @PatchMapping("/{reminderId}")
    public ReminderResponse update(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID reminderId,
            @Valid @RequestBody UpdateReminderRequest request) {
        return reminderService.update(requireUserId(principal), conversationId, reminderId, request);
    }

    @DeleteMapping("/{reminderId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID reminderId) {
        reminderService.delete(requireUserId(principal), conversationId, reminderId);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
