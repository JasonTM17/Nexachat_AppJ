package com.nexachat.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Pins the response security headers that are not already covered by the
 * endpoint contracts.
 *
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — a stored upload can never be
 *       re-interpreted as HTML/JS by a browser.</li>
 *   <li>{@code X-Frame-Options: DENY} — no endpoint is meant to be framed.</li>
 *   <li>{@code Referrer-Policy: no-referrer} — the API never needs to leak its
 *       URLs to third parties.</li>
 *   <li>{@code Cache-Control: no-store} on {@code /api/v1/auth/**} — token
 *       responses (and their failures) must not be written to any cache.</li>
 * </ul>
 *
 * <p>Registered after Spring Security's {@code HeaderWriterFilter} so the
 * auth-only cache policy can replace the global cache header. The bean is also
 * auto-registered as a plain servlet filter; {@link OncePerRequestFilter}'s
 * already-filtered guard makes that second registration a no-op, and the
 * in-chain execution is the one that runs before the response is committed.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String AUTH_PREFIX = "/api/v1/auth/";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull jakarta.servlet.FilterChain filterChain)
            throws jakarta.servlet.ServletException, java.io.IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.getRequestURI() != null && request.getRequestURI().startsWith(AUTH_PREFIX)) {
            response.setHeader("Cache-Control", "no-store");
        }
        filterChain.doFilter(request, response);
    }
}
