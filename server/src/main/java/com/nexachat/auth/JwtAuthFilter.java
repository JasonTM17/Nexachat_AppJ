package com.nexachat.auth;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.user.UserRepository;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Parses {@code Authorization: Bearer <jwt>} once per request.
 *
 * <ul>
 *   <li>No Bearer header → the chain continues unauthenticated; the entry point
 *       answers 401 for protected endpoints (permitAll endpoints unaffected).</li>
 *   <li>Valid token → {@link JwtPrincipal} (typed userId) becomes the
 *       Authentication principal — the documented principal choice.</li>
 *   <li>Bad/expired token → no authentication is set and the precise error code is
 *       stashed in {@link #AUTH_ERROR_ATTRIBUTE} so the entry point can report
 *       {@code TOKEN_EXPIRED}/{@code TOKEN_INVALID} instead of a generic 401.</li>
 * </ul>
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "nexachat.authErrorCode";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                JwtPrincipal principal = jwtService.parseAccessToken(header.substring(BEARER_PREFIX.length()));
                if (userRepository.existsById(principal.userId())) {
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    request.setAttribute(AUTH_ERROR_ATTRIBUTE, ApiErrorCode.TOKEN_INVALID);
                }
            } catch (ExpiredJwtException e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ApiErrorCode.TOKEN_EXPIRED);
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ApiErrorCode.TOKEN_INVALID);
            }
        }
        filterChain.doFilter(request, response);
    }
}
