package com.nexachat.auth;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
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

    private static final int MIN_SECRET_BYTES = 32; // 256-bit minimum for HS256

    private final SecretKey key;
    private final long accessTtlSeconds;

    public JwtService(JwtProperties properties) {
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
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * Verified access-token material: the caller id plus the second-precision
     * issue instant the revocation cutoff is compared against.
     */
    public record AccessTokenClaims(UUID userId, Instant issuedAt, Instant expiresAt) {
        /** Compatibility for callers that provide an already-verified test claim. */
        public AccessTokenClaims(UUID userId, Instant issuedAt) {
            this(userId, issuedAt, Instant.MAX);
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
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("Token is not an access token");
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
                    expiresAt.toInstant());
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
        return new JwtPrincipal(parseAccessTokenClaims(token).userId());
    }

    /** Value echoed to clients as {@code accessTokenExpiresIn}. */
    public long accessTokenExpiresIn() {
        return accessTtlSeconds;
    }
}
