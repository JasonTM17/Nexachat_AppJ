package com.nexachat.auth;

import com.nexachat.auth.google.GoogleSignInProperties;
import com.nexachat.config.ClientIpResolver;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Per-IP rate limit on credential and account-recovery endpoints:
 * <ul>
 *   <li>POST /api/v1/auth/login    — 10 requests / 5 min</li>
 *   <li>POST /api/v1/auth/register — 10 requests / 5 min</li>
 *   <li>POST /api/v1/auth/refresh  — 30 requests / 5 min</li>
 *   <li>Public password-reset and account-deletion routes — 30 requests / 5 min each</li>
 *   <li>Authenticated recovery-email request and verification — 30 requests / 5 min each</li>
 *   <li>POST /api/v1/auth/google/nonce — 30 requests / 5 min (only while the feature is on)</li>
 *   <li>POST /api/v1/auth/google       — 20 requests / 5 min (only while the feature is on)</li>
 * </ul>
 * Each rule gets its own bucket per client IP. Implemented as an interceptor
 * (not a servlet filter) so rejections flow through {@code GlobalExceptionHandler}
 * and keep the standard error envelope: {@code 429 {"error":{"code":"RATE_LIMITED"}}}.
 *
 * <p>Client IP comes from {@link ClientIpResolver}: the socket peer by default,
 * or the framework-rewritten address when the peer matches a configured
 * trusted reverse proxy ({@code nexachat.security.trusted-proxy-cidrs}).
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private record Rule(HttpMethod method, String name, String path, long capacity, Duration period) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(HttpMethod.POST, "login", "/api/v1/auth/login", 10, Duration.ofMinutes(5)),
            new Rule(HttpMethod.POST, "register", "/api/v1/auth/register", 10, Duration.ofMinutes(5)),
            new Rule(HttpMethod.POST, "refresh", "/api/v1/auth/refresh", 30, Duration.ofMinutes(5)),
            new Rule(HttpMethod.POST, "account-deletion-request", "/api/v1/account-deletion/request",
                    30, Duration.ofMinutes(5)),
            new Rule(HttpMethod.POST, "account-deletion-confirm", "/api/v1/account-deletion/confirm",
                    30, Duration.ofMinutes(5)),
            new Rule(HttpMethod.POST, "password-reset-request", "/api/v1/auth/password-reset/request",
                    30, Duration.ofMinutes(5)),
            new Rule(HttpMethod.POST, "password-reset-confirm", "/api/v1/auth/password-reset/confirm",
                    30, Duration.ofMinutes(5)),
            new Rule(HttpMethod.PUT, "recovery-email-request", "/api/v1/users/me/email",
                    30, Duration.ofMinutes(5)),
            new Rule(HttpMethod.POST, "recovery-email-confirm", "/api/v1/users/me/email/verify",
                    30, Duration.ofMinutes(5)));

    /**
     * Google buckets exist only while the feature is enabled: a disabled
     * endpoint must answer 404 even after a burst, never 429.
     */
    private static final List<Rule> GOOGLE_RULES = List.of(
            // A nonce is requested before every attempt, so it needs more
            // headroom than the sign-in call itself.
            new Rule(HttpMethod.POST, "google-nonce", "/api/v1/auth/google/nonce", 30, Duration.ofMinutes(5)),
            new Rule(HttpMethod.POST, "google-signin", "/api/v1/auth/google", 20, Duration.ofMinutes(5)));

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    /**
     * Optional: MVC slice tests import the interceptor without the Google
     * configuration, and a missing bean means "feature off".
     */
    private final ObjectProvider<GoogleSignInProperties> googleSignInProperties;

    public RateLimitInterceptor(RateLimitService rateLimitService,
                                ClientIpResolver clientIpResolver,
                                ObjectProvider<GoogleSignInProperties> googleSignInProperties) {
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
        this.googleSignInProperties = googleSignInProperties;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String uri = request.getRequestURI();
        List<Rule> rules = googleEnabled()
                ? java.util.stream.Stream.concat(RULES.stream(), GOOGLE_RULES.stream()).toList()
                : RULES;
        for (Rule rule : rules) {
            if (rule.method().matches(request.getMethod()) && rule.path().equals(uri)) {
                String key = rule.name() + '|' + clientIpResolver.rateLimitClientIp(request);
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

    private boolean googleEnabled() {
        GoogleSignInProperties properties = googleSignInProperties.getIfAvailable();
        return properties != null && properties.enabled();
    }
}
