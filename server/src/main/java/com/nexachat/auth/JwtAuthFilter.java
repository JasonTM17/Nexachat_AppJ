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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
 * row carries a cutoff instant; a token whose {@code iat} is at or before it is
 * rejected with {@code TOKEN_INVALID}. The account-exists-plus-cutoff lookup is
 * served from {@link AccessTokenValidityCache} so the hot path does no database
 * work, falling back to the database on a cache miss.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "nexachat.authErrorCode";
    public static final String ACCESS_TOKEN_ID_ATTRIBUTE = "nexachat.accessTokenId";
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
                Validity validity = loadValidity(claims.userId());
                if (!validity.present()) {
                    request.setAttribute(AUTH_ERROR_ATTRIBUTE, ApiErrorCode.TOKEN_INVALID);
                } else if (validity.suspended()) {
                    // A suspended account's tokens die regardless of issue time:
                    // suspend also stamps the cutoff, but a token minted in the
                    // same second must still fail closed.
                    request.setAttribute(AUTH_ERROR_ATTRIBUTE, ApiErrorCode.ACCOUNT_SUSPENDED);
                } else if (isRevoked(claims, validity)) {
                    request.setAttribute(AUTH_ERROR_ATTRIBUTE, ApiErrorCode.TOKEN_INVALID);
                } else {
                    if (claims.tokenId() != null && !claims.tokenId().isBlank()) {
                        request.setAttribute(ACCESS_TOKEN_ID_ATTRIBUTE, claims.tokenId());
                    }
                    var authentication = new UsernamePasswordAuthenticationToken(
                            new JwtPrincipal(claims.userId(), claims.issuedAt(), claims.expiresAt()),
                            null, authoritiesFor(validity.role()));
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
     * Resolves the account's revocation facts, served from the bounded cache
     * first and falling back to the database on a miss so the hot path does no
     * per-request SQL in the steady state.
     */
    private Validity loadValidity(UUID userId) {
        Optional<Validity> cached = validityCache.get(userId);
        if (cached.isPresent()) {
            return cached.get();
        }
        long generation = validityCache.generation();
        Validity loaded = userRepository.findAuthValidityById(userId)
                .map(found -> new Validity(true, found.tokensValidAfter(),
                        found.role() == null ? null : found.role().name(), found.suspended()))
                .orElseGet(Validity::missing);
        validityCache.putIfGeneration(userId, loaded, generation);
        return loaded;
    }

    /**
     * True when the token was issued at or before the cutoff. A token without
     * an {@code iat} is treated as issued at the epoch, so any cutoff rejects
     * it. Inclusive comparison fails closed for the cutoff second because JWT
     * {@code iat} has one-second resolution.
     */
    private boolean isRevoked(JwtService.AccessTokenClaims claims, Validity validity) {
        Instant cutoff = validity.tokensValidAfter();
        return cutoff != null && !claims.issuedAt().isAfter(cutoff);
    }

    /**
     * Maps the stored role to Spring authorities. {@code ROLE_} prefix is the
     * framework convention consumed by {@code hasRole} matchers; a missing or
     * unknown role yields the empty list, which fails closed on every
     * role-gated route.
     */
    private static List<SimpleGrantedAuthority> authoritiesFor(String role) {
        if (role == null) {
            return List.of();
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
