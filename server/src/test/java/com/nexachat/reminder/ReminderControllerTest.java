package com.nexachat.reminder;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.reminder.dto.CreateReminderRequest;
import com.nexachat.reminder.dto.ReminderResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReminderControllerTest {

    private final ReminderService reminderService = mock(ReminderService.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final ReminderController controller =
            new ReminderController(reminderService, rateLimitService, 30);
    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @Test
    void createReturns201ForANewReminder() {
        CreateReminderRequest request = new CreateReminderRequest(
                "Hop team", Instant.now().plusSeconds(3600), "WEEKLY");
        ReminderResponse reminder = response();
        when(rateLimitService.tryConsume(anyString(), eq(30L), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(true, 0));
        when(reminderService.create(userId, conversationId, request, "key-1"))
                .thenReturn(new ReminderCreateResult(reminder, true));

        ResponseEntity<ReminderResponse> result = controller.create(
                new JwtPrincipal(userId), conversationId, request, "key-1");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(reminder);
    }

    @Test
    void createReturns200ForAnIdempotentReplay() {
        CreateReminderRequest request = new CreateReminderRequest(
                "Hop team", Instant.now().plusSeconds(3600), "NONE");
        ReminderResponse reminder = response();
        when(rateLimitService.tryConsume(anyString(), eq(30L), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(true, 0));
        when(reminderService.create(userId, conversationId, request, "replay-key"))
                .thenReturn(new ReminderCreateResult(reminder, false));

        ResponseEntity<ReminderResponse> result = controller.create(
                new JwtPrincipal(userId), conversationId, request, "replay-key");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(reminder);
    }

    @Test
    void createFailsClosedWhenThePerUserBucketIsEmpty() {
        CreateReminderRequest request = new CreateReminderRequest(
                "Hop team", Instant.now().plusSeconds(3600), "NONE");
        when(rateLimitService.tryConsume(anyString(), eq(30L), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(false, 17));

        assertThatThrownBy(() -> controller.create(
                new JwtPrincipal(userId), conversationId, request, null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
                    assertThat(exception.getDetails())
                            .containsEntry("retryAfterSeconds", 17L);
                });
        verifyNoInteractions(reminderService);
    }

    @Test
    void missingPrincipalIsUnauthorized() {
        assertThatThrownBy(() -> controller.list(null, conversationId))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ApiErrorCode.UNAUTHORIZED));
    }

    @Test
    void rateLimitedCreateNeverReachesTheService() {
        CreateReminderRequest request = new CreateReminderRequest(
                "Hop team", Instant.now().plusSeconds(3600), "NONE");
        when(rateLimitService.tryConsume(anyString(), eq(30L), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(false, 1));

        assertThatThrownBy(() -> controller.create(
                new JwtPrincipal(userId), conversationId, request, "key"))
                .isInstanceOf(ApiException.class);
        verify(reminderService, org.mockito.Mockito.never())
                .create(any(), any(), any(), any());
    }

    private ReminderResponse response() {
        Instant now = Instant.now();
        return new ReminderResponse(UUID.randomUUID(), conversationId, "Hop team",
                now.plusSeconds(3600), ReminderRepeat.WEEKLY, userId, "An", true, now, now);
    }
}
