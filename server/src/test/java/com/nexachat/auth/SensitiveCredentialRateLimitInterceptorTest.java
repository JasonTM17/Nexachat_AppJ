package com.nexachat.auth;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SensitiveCredentialRateLimitInterceptorTest {

    private RateLimitService rateLimitService;
    private SensitiveCredentialRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(100, Duration.ofMinutes(10));
        interceptor = new SensitiveCredentialRateLimitInterceptor(rateLimitService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtPrincipal(UUID.randomUUID()), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accountDeletionRouteSharesTheCredentialConfirmBucket() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/users/me");
        for (int attempt = 0; attempt < SensitiveCredentialRateLimitInterceptor.CAPACITY; attempt++) {
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        }
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApiException.class);
    }
}
