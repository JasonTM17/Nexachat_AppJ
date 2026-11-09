package com.nexachat.user;

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
 * Per-user rate limit on {@code GET /api/v1/users/search} — 30 requests per
 * minute (task spec; note phase-06 mentions "30/h", the minute window was the
 * confirmed figure).
 *
 * <p>Design choices, documented per the task brief:
 * <ul>
 *   <li>A dedicated interceptor (not an edit to {@code RateLimitInterceptor}):
 *       the auth one is IP-keyed, POST-only and scoped to /auth/** — extending
 *       it would drag auth-package changes this phase does not own. This one
 *       lives next to the endpoint it guards and is registered in
 *       {@code WebConfig} on the exact path pattern.</li>
 *   <li>Keyed by user id, not IP: the endpoint sits behind Bearer auth, so the
 *       {@link JwtPrincipal} from the SecurityContext is the honest identity —
 *       and per-user (not per-IP) is what the spec asks for. Runs inside the
 *       DispatcherServlet after the security filter, so the principal is
 *       already populated; an absent principal falls back to remote IP and a
 *       401 from the entry point would have fired anyway.</li>
 *   <li>Throws {@link ApiException} like the auth interceptor so rejections
 *       keep the standard {@code 429 {"error":{"code":"RATE_LIMITED"}}}
 *       envelope via {@code GlobalExceptionHandler}.</li>
 * </ul>
 */
@Component
public class UserSearchRateLimitInterceptor implements HandlerInterceptor {

    static final long SEARCH_CAPACITY = 30;
    static final Duration SEARCH_PERIOD = Duration.ofMinutes(1);
    static final String BUCKET_PREFIX = "user-search|";

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    public UserSearchRateLimitInterceptor(RateLimitService rateLimitService,
                                          ClientIpResolver clientIpResolver) {
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        String key = BUCKET_PREFIX + principalKey(request, clientIpResolver);
        RateLimitService.Consumption consumption =
                rateLimitService.tryConsume(key, SEARCH_CAPACITY, SEARCH_PERIOD);
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
        // Unauthenticated calls are rejected by the security entry point anyway;
        // this is a belt-and-suspenders key so a stray path through still limits.
        return "ip:" + clientIpResolver.rateLimitClientIp(request);
    }
}
