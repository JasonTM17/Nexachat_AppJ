package com.nexachat.scheduled;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.scheduled.dto.CreateScheduledMessageRequest;
import com.nexachat.scheduled.dto.ScheduledMessageResponse;
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

class ScheduledMessageControllerTest {

    private final ScheduledMessageService scheduledMessageService =
            mock(ScheduledMessageService.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final ScheduledMessageController controller =
            new ScheduledMessageController(scheduledMessageService, rateLimitService, 30);
    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @Test
    void createReturns201ForANewScheduledMessage() {
        CreateScheduledMessageRequest request = request();
        ScheduledMessageResponse scheduled = response();
        when(rateLimitService.tryConsume(anyString(), eq(30L), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(true, 0));
        when(scheduledMessageService.create(userId, conversationId, request))
                .thenReturn(new ScheduledMessageCreateResult(scheduled, true));

        ResponseEntity<ScheduledMessageResponse> result = controller.create(
                new JwtPrincipal(userId), conversationId, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(scheduled);
    }

    @Test
    void createReturns200ForAnIdempotentReplay() {
        CreateScheduledMessageRequest request = request();
        ScheduledMessageResponse scheduled = response();
        when(rateLimitService.tryConsume(anyString(), eq(30L), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(true, 0));
        when(scheduledMessageService.create(userId, conversationId, request))
                .thenReturn(new ScheduledMessageCreateResult(scheduled, false));

        ResponseEntity<ScheduledMessageResponse> result = controller.create(
                new JwtPrincipal(userId), conversationId, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(scheduled);
    }

    @Test
    void createFailsClosedWhenThePerUserBucketIsEmpty() {
        when(rateLimitService.tryConsume(anyString(), eq(30L), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(false, 17));

        assertThatThrownBy(() -> controller.create(
                new JwtPrincipal(userId), conversationId, request()))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
                    assertThat(exception.getDetails())
                            .containsEntry("retryAfterSeconds", 17L);
                });
        verifyNoInteractions(scheduledMessageService);
    }

    @Test
    void missingPrincipalIsUnauthorized() {
        assertThatThrownBy(() -> controller.list(null, conversationId))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ApiErrorCode.UNAUTHORIZED));
        assertThatThrownBy(() -> controller.create(null, conversationId, request()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ApiErrorCode.UNAUTHORIZED));
        assertThatThrownBy(() -> controller.cancel(null, conversationId, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ApiErrorCode.UNAUTHORIZED));
    }

    @Test
    void rateLimitedCreateNeverReachesTheService() {
        when(rateLimitService.tryConsume(anyString(), eq(30L), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(false, 1));

        assertThatThrownBy(() -> controller.create(
                new JwtPrincipal(userId), conversationId, request()))
                .isInstanceOf(ApiException.class);
        verify(scheduledMessageService, org.mockito.Mockito.never())
                .create(any(), any(), any());
    }

    @Test
    void cancelReturns204() {
        ResponseEntity<Void> result = controller.cancel(
                new JwtPrincipal(userId), conversationId, UUID.randomUUID());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private CreateScheduledMessageRequest request() {
        return new CreateScheduledMessageRequest(
                UUID.randomUUID(), "Nhắc họp 9h", Instant.now().plusSeconds(3600));
    }

    private ScheduledMessageResponse response() {
        Instant now = Instant.now();
        return new ScheduledMessageResponse(UUID.randomUUID(), conversationId,
                UUID.randomUUID(), "Nhắc họp 9h", now.plusSeconds(3600),
                "PENDING", null, null, now, now);
    }
}
