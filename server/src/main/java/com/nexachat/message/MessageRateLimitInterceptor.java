package com.nexachat.message;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class MessageRateLimitInterceptor implements HandlerInterceptor {

    private static final Pattern MESSAGE_SEND =
            Pattern.compile("^/api/v1/conversations/[^/]+/messages$");
    private static final Pattern MESSAGE_MUTATION =
            Pattern.compile("^/api/v1/conversations/[^/]+/messages/[^/]+$");
    private static final String SEARCH_PATH = "/messages/search";

    private final RateLimitService rateLimitService;
    private final long sendPerWindow;
    private final long sendWindowSeconds;
    private final long editDeletePerMinute;
    private final long searchPerMinute;

    public MessageRateLimitInterceptor(
            RateLimitService rateLimitService,
            @Value("${nexachat.rate-limit.stomp.messages-per-window:30}") long sendPerWindow,
            @Value("${nexachat.rate-limit.stomp.window-seconds:10}") long sendWindowSeconds,
            @Value("${nexachat.rate-limit.messages.edit-delete-per-minute:30}") long editDeletePerMinute,
            @Value("${nexachat.rate-limit.messages.search-per-minute:60}") long searchPerMinute) {
        this.rateLimitService = rateLimitService;
        this.sendPerWindow = sendPerWindow;
        this.sendWindowSeconds = sendWindowSeconds;
        this.editDeletePerMinute = editDeletePerMinute;
        this.searchPerMinute = searchPerMinute;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        String bucket;
        long capacity;
        Duration refillPeriod;
        if ("POST".equals(method) && MESSAGE_SEND.matcher(path).matches()) {
            bucket = "message-send|";
            capacity = sendPerWindow;
            refillPeriod = Duration.ofSeconds(sendWindowSeconds);
        } else if (("PATCH".equals(method) || "DELETE".equals(method))
                && MESSAGE_MUTATION.matcher(path).matches()) {
            bucket = "message-write|";
            capacity = editDeletePerMinute;
            refillPeriod = Duration.ofMinutes(1);
        } else if ("GET".equals(method) && path.endsWith(SEARCH_PATH)) {
            bucket = "message-search|";
            capacity = searchPerMinute;
            refillPeriod = Duration.ofMinutes(1);
        } else {
            return true;
        }

        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                bucket + principalKey(), capacity, refillPeriod);
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED,
                    "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
        return true;
    }

    private static String principalKey() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtPrincipal principal) {
            return "user:" + principal.userId();
        }
        return "anonymous";
    }
}
