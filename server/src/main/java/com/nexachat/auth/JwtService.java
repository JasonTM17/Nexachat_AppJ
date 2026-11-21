package com.nexachat.auth;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * HS256 access-token issue/verify (jjwt 0.12.x fluent API).
 *
 * <p>Claims: {@code sub}=userId (UUID string), {@code iat}, {@code exp}=iat+900s,
 * {@code type}="access". The {@code type} claim is checked on parse so a token that
 * is not an access token can never be replayed against protected endpoints.
 *
 * <p>HS256 is right here because one service both signs and verifies (T14); RS256
 * only pays off when independent parties must verify without the private key.
 */
@Service
public class JwtService {

    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "access";
    /** Binds an access token to this service. Tokens for any other issuer or audience are rejected. */
    public static final String ISSUER = "nexachat";
    public static final String AUDIENCE = "nexachat-api";

    private static final int MIN_SECRET_BYTES = 32; // 256-bit minimum for HS256

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final Clock clock;

    /**
     * Access tokens are minted on the real wall clock — never the shared
     * Spring {@code Clock} bean, which tests freeze to pin unrelated
     * rate-limit windows; a token minted under a frozen clock would be
     * born already expired.
     */
    @Autowired
    public JwtService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Pinned-clock seam for tests that must control the mint instant. */
    public JwtService(JwtProperties properties, Clock clock) {
        byte[] secretBytes = properties.secret() == null
                ? new byte[0]
                : properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            // Fail fast at boot instead of signing with a weak key (NFR-3).
            throw new IllegalStateException(
                    "nexachat.jwt.secret must be at least 32 bytes (256-bit) for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessTtlSeconds = properties.accessTtlSeconds();
        this.clock = clock;
    }

    /**
     * Mints an access token whose {@code iat} survives the revocation cutoff.
     *
     * <p>JWT {@code iat} has one-second resolution and {@link JwtAuthFilter}
     * rejects {@code iat <= cutoff} inclusively, so a token minted in the same
     * wall-clock second as a just-stamped cutoff (logout-all then immediate
     * re-login on a fast stack) would be born dead for its whole TTL. When the
     * fresh, locked user snapshot carries a cutoff at or after the mint
     * second, the issue instant moves one second past the cutoff. Tokens
     * issued before a later revocation keep {@code iat <= cutoff} and still
     * die; the callers hold the owner row lock, so the cutoff they see cannot
     * be older than the one a concurrent revocation would stamp.
     */
    public String generateAccessToken(User user) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant cutoff = user.getTokensValidAfter();
        if (cutoff != null && !issuedAt.isAfter(cutoff)) {
            issuedAt = cutoff.plusSeconds(1);
        }
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().single(AUDIENCE)
                .subject(user.getId().toString())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(accessTtlSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * Verified access-token material: the caller id plus the second-precision
     * issue instant the revocation cutoff is compared against.
     */
    public record AccessTokenClaims(UUID userId, Instant issuedAt, Instant expiresAt, String tokenId) {
        /** Compatibility for verified claims that do not include a token identifier. */
        public AccessTokenClaims(UUID userId, Instant issuedAt, Instant expiresAt) {
            this(userId, issuedAt, expiresAt, null);
        }

        /** Compatibility for callers that provide an already-verified test claim. */
        public AccessTokenClaims(UUID userId, Instant issuedAt) {
            this(userId, issuedAt, Instant.MAX, null);
        }
    }

    /**
     * Parses and verifies an access token, keeping the issue instant.
     *
     * @throws io.jsonwebtoken.ExpiredJwtException when the token is past {@code exp}
     * @throws JwtException for any other signature/format/claim problem
     */
    public AccessTokenClaims parseAccessTokenClaims(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clockSkewSeconds(0)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("Token is not an access token");
        }
        if (!ISSUER.equals(claims.getIssuer())) {
            throw new JwtException("Token issuer is not accepted");
        }
        Set<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(AUDIENCE)) {
            throw new JwtException("Token audience is not accepted");
        }
        String subject = claims.getSubject();
        if (subject == null) {
            throw new JwtException("Token has no subject");
        }
        try {
            Date issuedAt = claims.getIssuedAt();
            Date expiresAt = claims.getExpiration();
            if (expiresAt == null) {
                throw new JwtException("Token has no expiration");
            }
            return new AccessTokenClaims(
                    UUID.fromString(subject),
                    issuedAt == null ? Instant.EPOCH : issuedAt.toInstant(),
                    expiresAt.toInstant(),
                    claims.getId());
        } catch (IllegalArgumentException e) {
            throw new JwtException("Token subject is not a user id", e);
        }
    }

    /**
     * @return the authenticated principal
     * @throws io.jsonwebtoken.ExpiredJwtException when the token is past {@code exp}
     * @throws JwtException for any other signature/format/claim problem
     */
    public JwtPrincipal parseAccessToken(String token) {
        AccessTokenClaims claims = parseAccessTokenClaims(token);
        return new JwtPrincipal(claims.userId(), claims.issuedAt(), claims.expiresAt());
    }

    /** Value echoed to clients as {@code accessTokenExpiresIn}. */
    public long accessTokenExpiresIn() {
        return accessTtlSeconds;
    }
}
