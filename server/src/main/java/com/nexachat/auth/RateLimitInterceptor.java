package com.nexachat.auth;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Per-IP rate limit on the credential endpoints (plan T15 / §13 brute-force row):
 * <ul>
 *   <li>POST /api/v1/auth/login    — 10 requests / 5 min</li>
 *   <li>POST /api/v1/auth/register — 10 requests / 5 min</li>
 *   <li>POST /api/v1/auth/refresh  — 30 requests / 5 min</li>
 * </ul>
 * Each rule gets its own bucket per client IP. Implemented as an interceptor
 * (not a servlet filter) so rejections flow through {@code GlobalExceptionHandler}
 * and keep the standard error envelope: {@code 429 {"error":{"code":"RATE_LIMITED"}}}.
 *
 * <p>Client IP = {@code request.getRemoteAddr()} — honest for the single-node LAN
 * deployment; X-Forwarded-For is deliberately NOT trusted because it is
 * client-supplied and trivially spoofed without a trusted proxy.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private record Rule(String name, String path, long capacity, Duration period) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("login", "/api/v1/auth/login", 10, Duration.ofMinutes(5)),
            new Rule("register", "/api/v1/auth/register", 10, Duration.ofMinutes(5)),
            new Rule("refresh", "/api/v1/auth/refresh", 30, Duration.ofMinutes(5)));

    private final RateLimitService rateLimitService;

    public RateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        for (Rule rule : RULES) {
            if (rule.path().equals(uri)) {
                String key = rule.name() + '|' + request.getRemoteAddr();
                RateLimitService.Consumption consumption =
                        rateLimitService.tryConsume(key, rule.capacity(), rule.period());
                if (!consumption.allowed()) {
                    throw ApiException.of(
                            ApiErrorCode.RATE_LIMITED,
                            "Rate limit exceeded, try again later",
                            Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
                }
            }
        }
        return true;
    }
}
