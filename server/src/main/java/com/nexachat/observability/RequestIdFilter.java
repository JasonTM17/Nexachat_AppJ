package com.nexachat.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Assigns every request a correlation id: the inbound {@code X-Request-Id}
 * when it is safe, otherwise a fresh UUID. The id is placed in MDC under
 * {@code traceId} (and {@code correlationId} for the default console pattern),
 * echoed on the response, and cleared after the request.
 *
 * <p>Only a bounded, non-whitespace shape is accepted from the client so the
 * id cannot be used for log injection.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";
    public static final String MDC_CORRELATION_KEY = "correlationId";

    private static final int MAX_LENGTH = 64;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER));
        try {
            MDC.put(MDC_KEY, requestId);
            MDC.put(MDC_CORRELATION_KEY, requestId);
            response.setHeader(HEADER, requestId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_CORRELATION_KEY);
        }
    }

    /** Returns a safe inbound id or a generated one. */
    String resolveRequestId(String raw) {
        if (raw == null) {
            return UUID.randomUUID().toString();
        }
        String candidate = raw.strip();
        return SAFE_ID.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }
}
