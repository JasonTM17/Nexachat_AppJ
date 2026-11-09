package com.nexachat.user;

import com.nexachat.auth.RateLimitService;
import com.nexachat.config.ClientIpResolver;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSearchRateLimitInterceptorTest {

    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ClientIpResolver clientIpResolver;

    private UserSearchRateLimitInterceptor interceptor;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        interceptor = new UserSearchRateLimitInterceptor(rateLimitService, clientIpResolver);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        var auth = new UsernamePasswordAuthenticationToken(new JwtPrincipal(userId), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void get_withPrincipal_consumesUserKeyedBucketAndPasses() {
        authenticate();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/search");
        when(rateLimitService.tryConsume(anyString(), anyLong(), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(true, 0));

        boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(result).isTrue();
        verify(rateLimitService).tryConsume(
                "user-search|user:" + userId, 30L, Duration.ofMinutes(1));
    }

    @Test
    void get_bucketExhausted_throwsRateLimitedWithRetryAfter() {
        authenticate();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/search");
        when(rateLimitService.tryConsume(anyString(), anyLong(), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(false, 42));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
        assertThat(ex.getHttpStatus()).isEqualTo(429);
        assertThat(ex.getDetails()).containsEntry("retryAfterSeconds", 42L);
    }

    @Test
    void nonGet_passesThroughWithoutConsuming() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users/search");

        boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(result).isTrue();
        verify(rateLimitService, never()).tryConsume(anyString(), anyLong(), any());
    }

    @Test
    void get_withoutPrincipal_fallsBackToIpKeyedBucket() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/search");
        request.setRemoteAddr("203.0.113.7");
        when(clientIpResolver.rateLimitClientIp(request)).thenReturn("203.0.113.7");
        when(rateLimitService.tryConsume(anyString(), anyLong(), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(true, 0));

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(rateLimitService).tryConsume(
                "user-search|ip:203.0.113.7", 30L, Duration.ofMinutes(1));
    }
}
