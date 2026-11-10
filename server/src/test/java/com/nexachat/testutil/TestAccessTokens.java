package com.nexachat.testutil;

import com.nexachat.auth.JwtProperties;
import com.nexachat.auth.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints signed access tokens with an explicit issue instant.
 *
 * <p>Access-token revocation stores its cutoff at whole-second precision (JWT
 * {@code iat} has no sub-second component), so a token issued in the same
 * second as the logout is still accepted by design. Tests that need a token
 * issued strictly before a cutoff stamp the {@code iat} here instead of
 * sleeping into the next wall-clock second.
 */
public final class TestAccessTokens {

    private TestAccessTokens() {
    }

    public static String accessTokenIssuedAt(JwtProperties properties, UUID userId, Instant issuedAt) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        return Jwts.builder()
                .issuer(JwtService.ISSUER)
                .audience().single(JwtService.AUDIENCE)
                .subject(userId.toString())
                .claim(JwtService.CLAIM_TYPE, JwtService.TYPE_ACCESS)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(properties.accessTtlSeconds())))
                .signWith(Keys.hmacShaKeyFor(secret))
                .compact();
    }
}
