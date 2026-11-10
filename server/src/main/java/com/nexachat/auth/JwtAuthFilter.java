package com.nexachat.auth;

import com.nexachat.auth.AccessTokenValidityCache.Validity;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Parses {@code Authorization: Bearer <jwt>} once per request.
 *
 * <ul>
 *   <li>No Bearer header → the chain continues unauthenticated; the entry point
 *       answers 401 for protected endpoints (permitAll endpoints unaffected).</li>
 *   <li>Valid token → {@link com.nexachat.common.dto.JwtPrincipal} (typed userId)
 *       becomes the Authentication principal — the documented principal choice.</li>
 *   <li>Bad/expired token → no authentication is set and the precise error code is
 *       stashed in {@link #AUTH_ERROR_ATTRIBUTE} so the entry point can report
 *       {@code TOKEN_EXPIRED}/{@code TOKEN_INVALID} instead of a generic 401.</li>
 * </ul>
 *
 * <p><b>Revocation:</b> after a logout, logout-all or password change the user
 * row carries a cutoff instant; a token whose {@code iat} is not after it is
 * rejected with {@code TOKEN_INVALID}. The account-exists-plus-cutoff lookup is
 * served from {@link AccessTokenValidityCache} so the hot path does no database
 * work, falling back to the database on a cache miss.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "nexachat.authErrorCode";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AccessTokenValidityCache validityCache;

    public JwtAuthFilter(JwtService jwtService,
                         UserRepository userRepository,
                         AccessTokenValidityCache validityCache) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.validityCache = validityCache;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                JwtService.AccessTokenClaims claims =
                        jwtService.parseAccessTokenClaims(header.substring(BEARER_PREFIX.length()));
                if (isRevoked(claims)) {
                    request.setAttribute(AUTH_ERROR_ATTRIBUTE, ApiErrorCode.TOKEN_INVALID);
                } else {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            new JwtPrincipal(claims.userId(), claims.issuedAt(), claims.expiresAt()), null, List.of());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (ExpiredJwtException e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ApiErrorCode.TOKEN_EXPIRED);
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ApiErrorCode.TOKEN_INVALID);
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * True when the account is gone or the token was issued before the cutoff.
     * A token without an {@code iat} is treated as issued at the epoch, so any
     * cutoff rejects it. Tokens issued in the cutoff's own second are accepted:
     * {@code iat} has one-second resolution, so they are indistinguishable from
     * a token minted right after the logout.
     */
    private boolean isRevoked(JwtService.AccessTokenClaims claims) {
        Optional<Validity> cached = validityCache.get(claims.userId());
        Validity validity;
        if (cached.isPresent()) {
            validity = cached.get();
        } else {
            long generation = validityCache.generation();
            Validity loaded = userRepository.findAuthValidityById(claims.userId())
                    .map(found -> new Validity(true, found.tokensValidAfter()))
                    .orElseGet(Validity::missing);
            validityCache.putIfGeneration(claims.userId(), loaded, generation);
            validity = loaded;
        }
        if (!validity.present()) {
            return true;
        }
        Instant cutoff = validity.tokensValidAfter();
        return cutoff != null && claims.issuedAt().isBefore(cutoff);
    }
}
