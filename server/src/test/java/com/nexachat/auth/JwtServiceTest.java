package com.nexachat.auth;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-at-least-64-chars-long-0123456789";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private final JwtService jwtService = new JwtService(new JwtProperties(SECRET, 900, 14));

    private static User userWithId(UUID id) {
        User user = User.create("+84912345678", "hash", "Minh");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void generateAndParse_roundTripsUserId_withExpectedClaims() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userWithId(userId));

        JwtPrincipal principal = jwtService.parseAccessToken(token);
        assertThat(principal.userId()).isEqualTo(userId);

        Claims claims = Jwts.parser().verifyWith(KEY).build()
                .parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getIssuer()).isEqualTo(JwtService.ISSUER);
        assertThat(claims.getAudience()).containsExactly(JwtService.AUDIENCE);
        assertThat(claims.get(JwtService.CLAIM_TYPE, String.class)).isEqualTo(JwtService.TYPE_ACCESS);
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(900_000); // exp = iat + 900s
        JwtService.AccessTokenClaims verified = jwtService.parseAccessTokenClaims(token);
        assertThat(verified.issuedAt()).isEqualTo(claims.getIssuedAt().toInstant());
        assertThat(verified.expiresAt()).isEqualTo(claims.getExpiration().toInstant());
    }

    @Test
    void parse_expiredToken_throwsExpiredJwtException() {
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim(JwtService.CLAIM_TYPE, JwtService.TYPE_ACCESS)
                .issuedAt(new Date(System.currentTimeMillis() - 20_000))
                .expiration(new Date(System.currentTimeMillis() - 10_000))
                .signWith(KEY)
                .compact();

        assertThat(catchThrowableOfType(ExpiredJwtException.class, () -> jwtService.parseAccessToken(expired)))
                .isNotNull();
    }

    @Test
    void parse_tokenWithoutAccessTypeClaim_isRejected() {
        // A token signed by our key but not marked as an access token must not authenticate.
        String foreign = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim(JwtService.CLAIM_TYPE, "something-else")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY)
                .compact();

        assertThat(catchThrowableOfType(JwtException.class, () -> jwtService.parseAccessToken(foreign)))
                .isNotNull();
    }

    @Test
    void parse_accessTokenWithoutExpiration_isRejected() {
        String missingExpiration = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim(JwtService.CLAIM_TYPE, JwtService.TYPE_ACCESS)
                .issuedAt(Date.from(Instant.now()))
                .signWith(KEY)
                .compact();

        assertThat(catchThrowableOfType(JwtException.class,
                () -> jwtService.parseAccessTokenClaims(missingExpiration)))
                .isNotNull();
    }

    @Test
    void parse_tamperedSignature_throwsJwtException() {
        SecretKey otherKey = Keys.hmacShaKeyFor("different-secret-key-at-least-64-chars-long-abcdef".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim(JwtService.CLAIM_TYPE, JwtService.TYPE_ACCESS)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(otherKey)
                .compact();

        assertThat(catchThrowableOfType(JwtException.class, () -> jwtService.parseAccessToken(forged)))
                .isNotNull();
    }

    @Test
    void parse_tokenWithoutAudience_isRejected() {
        String unbound = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(JwtService.ISSUER)
                .claim(JwtService.CLAIM_TYPE, JwtService.TYPE_ACCESS)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY)
                .compact();

        assertThat(catchThrowableOfType(JwtException.class, () -> jwtService.parseAccessToken(unbound)))
                .isNotNull();
    }

    @Test
    void parse_noneAlgorithm_isRejected() {
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String body = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + UUID.randomUUID()
                        + "\",\"iss\":\"nexachat\",\"aud\":\"nexachat-api\",\"type\":\"access\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(catchThrowableOfType(JwtException.class,
                () -> jwtService.parseAccessToken(header + "." + body + ".")))
                .isNotNull();
    }

    @Test
    void constructor_secretBelow256Bits_failsFast() {
        assertThat(catchThrowableOfType(IllegalStateException.class, 
                () -> new JwtService(new JwtProperties("short", 900, 14))))
                .isNotNull();
    }
}
