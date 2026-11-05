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
            new MessageRateLimitInterceptor(rateLimitService, 30, 60);
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
    }

    @Test
    void unrelatedConversationReadIsNotLimited() {
        authenticate();

        assertThat(preHandle("GET", "/api/v1/conversations/room/messages")).isTrue();
        org.mockito.Mockito.verifyNoInteractions(rateLimitService);
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new JwtPrincipal(userId), "", java.util.List.of()));
    }

    private boolean preHandle(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }
}
