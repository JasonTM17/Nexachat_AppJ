package com.nexachat.message;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageRateLimitInterceptorTest {

    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final MessageRateLimitInterceptor interceptor =
            new MessageRateLimitInterceptor(rateLimitService, 30, 10, 30, 60);
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void editAndDeleteShareThirtyPerMinuteUserBucket() {
        authenticate();
        when(rateLimitService.tryConsume("message-write|user:" + userId, 30, Duration.ofMinutes(1)))
                .thenReturn(new RateLimitService.Consumption(true, 0));

        assertThat(preHandle("PATCH", "/api/v1/conversations/room/messages/message")).isTrue();
        assertThat(preHandle("DELETE", "/api/v1/conversations/room/messages/message")).isTrue();
        verify(rateLimitService, org.mockito.Mockito.times(2)).tryConsume(
                "message-write|user:" + userId, 30, Duration.ofMinutes(1));
    }

    @Test
    void searchUsesSixtyPerMinuteUserBucketAndRejectsExcess() {
        authenticate();
        when(rateLimitService.tryConsume("message-search|user:" + userId, 60, Duration.ofMinutes(1)))
                .thenReturn(new RateLimitService.Consumption(false, 17));

        ApiException error = catchThrowableOfType(ApiException.class,
                () -> preHandle("GET", "/api/v1/conversations/room/messages/search"));

        assertThat(error.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
        assertThat(error.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(error.getDetails()).containsEntry("retryAfterSeconds", 17L);
    }

    @Test
    void restMessageSendAllowsThirtiethRejectsThirtyFirstAndSeparatesPrincipals() {
        RateLimitService realRateLimitService = new RateLimitService();
        MessageRateLimitInterceptor sendInterceptor =
                new MessageRateLimitInterceptor(realRateLimitService, 30, 10, 30, 60);
        UUID firstUserId = UUID.randomUUID();
        UUID secondUserId = UUID.randomUUID();
        authenticate(firstUserId);
        String path = "/api/v1/conversations/" + UUID.randomUUID() + "/messages";

        for (int request = 0; request < 30; request++) {
            assertThat(preHandle(sendInterceptor, "POST", path)).isTrue();
        }
        ApiException error = catchThrowableOfType(ApiException.class,
                () -> preHandle(sendInterceptor, "POST", path));

        assertThat(error.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
        assertThat(error.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(error.getDetails()).containsKey("retryAfterSeconds");

        authenticate(secondUserId);
        assertThat(preHandle(sendInterceptor, "POST", path)).isTrue();
    }

    @Test
    void otherMethodsAndMessageSubroutesAreNotLimitedBySendBucket() {
        authenticate();

        assertThat(preHandle("GET", "/api/v1/conversations/room/messages")).isTrue();
        assertThat(preHandle("PATCH", "/api/v1/conversations/room/messages")).isTrue();
        assertThat(preHandle("POST", "/api/v1/conversations/room/read")).isTrue();
        assertThat(preHandle("POST", "/api/v1/messages/message/reactions")).isTrue();
        assertThat(preHandle("POST", "/api/v1/conversations/room/messages/message/reactions")).isTrue();
        org.mockito.Mockito.verifyNoInteractions(rateLimitService);
    }

    private void authenticate() {
        authenticate(userId);
    }

    private void authenticate(UUID authenticatedUserId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new JwtPrincipal(authenticatedUserId), "", java.util.List.of()));
    }

    private boolean preHandle(String method, String path) {
        return preHandle(interceptor, method, path);
    }

    private boolean preHandle(MessageRateLimitInterceptor target, String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return target.preHandle(request, new MockHttpServletResponse(), new Object());
    }
}
