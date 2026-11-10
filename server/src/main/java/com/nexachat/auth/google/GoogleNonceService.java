package com.nexachat.auth.google;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and consumes single-use sign-in nonces.
 *
 * <p>Only the SHA-256 hex of a nonce is stored. Consumption is one conditional
 * {@code UPDATE} (unconsumed and unexpired), so two concurrent sign-ins with
 * the same nonce cannot both win, even across application instances. Timing is
 * database-authoritative ({@code now()}), matching the refresh-token service.
 */
@Service
public class GoogleNonceService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 32;
    private static final int MAX_NONCE_LENGTH = 512;
    private static final int CLEANUP_BATCH = 200;

    /**
     * Resolved lazily: the {@code test} profile excludes DataSource
     * autoconfiguration, and a disabled Google feature must not need a database
     * to construct the application context.
     */
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final GoogleSignInProperties properties;

    public GoogleNonceService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                              GoogleSignInProperties properties) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.properties = properties;
    }

    /** @return the raw nonce (shown once) and its lifetime in seconds */
    public IssuedNonce issue() {
        byte[] bytes = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update(
                "INSERT INTO google_signin_nonces (nonce_hash, expires_at)"
                        + " VALUES (?, now() + (? * INTERVAL '1 second'))",
                sha256Hex(nonce), properties.nonceTtlSeconds());
        jdbcTemplate.update(
                "DELETE FROM google_signin_nonces WHERE id IN ("
                        + "SELECT id FROM google_signin_nonces WHERE expires_at < now() LIMIT "
                        + CLEANUP_BATCH + ")");
        return new IssuedNonce(nonce, properties.nonceTtlSeconds());
    }

    /**
     * Marks {@code nonce} consumed. Returns {@code false} for a malformed,
     * unknown, expired or already-consumed value — the caller must fail closed.
     */
    public boolean consume(String nonce) {
        if (nonce == null || nonce.isBlank() || nonce.length() > MAX_NONCE_LENGTH) {
            return false;
        }
        int updated = jdbcTemplate().update(
                "UPDATE google_signin_nonces SET consumed_at = now()"
                        + " WHERE nonce_hash = ? AND consumed_at IS NULL AND expires_at > now()",
                sha256Hex(nonce));
        return updated == 1;
    }

    private JdbcTemplate jdbcTemplate() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("Google sign-in needs a JDBC datasource");
        }
        return jdbcTemplate;
    }

    public static String sha256Hex(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Public projection: the raw value exists only in the HTTP response. */
    public record IssuedNonce(String nonce, long expiresInSeconds) {
    }
}
