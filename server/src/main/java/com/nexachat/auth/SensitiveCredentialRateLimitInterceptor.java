package com.nexachat.auth;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;

/**
 * Per-user rate limit on credential-confirming writes: PATCH
 * {@code /api/v1/auth/password} and DELETE {@code /api/v1/users/me}. A single
 * budget stops brute-forcing either surface in isolation.
 */
@Component
public class SensitiveCredentialRateLimitInterceptor implements HandlerInterceptor {

    static final long CAPACITY = 10;
    static final Duration PERIOD = Duration.ofMinutes(5);
    static final String BUCKET_PREFIX = "credential-confirm|";

    private final RateLimitService rateLimitService;

    public SensitiveCredentialRateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String uri = request.getRequestURI();
        boolean passwordChange = HttpMethod.PATCH.matches(request.getMethod())
                && "/api/v1/auth/password".equals(uri);
        boolean accountDeletion = HttpMethod.DELETE.matches(request.getMethod())
                && "/api/v1/users/me".equals(uri);
        if (!passwordChange && !accountDeletion) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
            return true;
        }
        String key = BUCKET_PREFIX + principal.userId();
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(key, CAPACITY, PERIOD);
        if (!consumption.allowed()) {
            throw ApiException.of(
                    ApiErrorCode.RATE_LIMITED,
                    "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
        return true;
    }
}
