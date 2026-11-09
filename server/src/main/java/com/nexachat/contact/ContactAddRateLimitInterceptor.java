package com.nexachat.contact;

import com.nexachat.auth.RateLimitService;
import com.nexachat.config.ClientIpResolver;
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
 * Per-user rate limit on {@code POST /api/v1/contacts} — 30 adds per minute,
 * same policy family as {@code UserSearchRateLimitInterceptor} (see its javadoc
 * for the per-user-keying rationale). Contact-add is a write that hits the DB
 * unique constraint; without a cap, scripted add-spam is the cheapest write
 * amplification in the API.
 */
@Component
public class ContactAddRateLimitInterceptor implements HandlerInterceptor {

    static final long ADD_CAPACITY = 30;
    static final Duration ADD_PERIOD = Duration.ofMinutes(1);
    static final String BUCKET_PREFIX = "contact-add|";

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public ContactAddRateLimitInterceptor(RateLimitService rateLimitService,
                                            ClientIpResolver clientIpResolver) {
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String key = BUCKET_PREFIX + principalKey(request, clientIpResolver);
        RateLimitService.Consumption consumption =
                rateLimitService.tryConsume(key, ADD_CAPACITY, ADD_PERIOD);
        if (!consumption.allowed()) {
            throw ApiException.of(
                    ApiErrorCode.RATE_LIMITED,
                    "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
        return true;
    }

    private static String principalKey(HttpServletRequest request, ClientIpResolver clientIpResolver) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtPrincipal principal) {
            return "user:" + principal.userId();
        }
        return "ip:" + clientIpResolver.rateLimitClientIp(request);
    }
}
