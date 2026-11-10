package com.nexachat.auth.google;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    /** Nonce purposes: a public sign-in nonce is not valid for the link endpoint. */
    public static final String PURPOSE_SIGNIN = "signin";
    public static final String PURPOSE_LINK = "link";

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

    /**
     * @param purpose {@link #PURPOSE_SIGNIN} (public, unowned) or {@link #PURPOSE_LINK}
     * @param userId  the account that may consume a link nonce; {@code null} for sign-in
     * @return the raw nonce (shown once) and its lifetime in seconds
     */
    public IssuedNonce issue(String purpose, java.util.UUID userId) {
        byte[] bytes = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.update(
                "INSERT INTO google_signin_nonces (nonce_hash, purpose, user_id, expires_at)"
                        + " VALUES (?, ?, ?, now() + (? * INTERVAL '1 second'))",
                sha256Hex(nonce), purpose, userId, properties.nonceTtlSeconds());
        jdbcTemplate.update(
                "DELETE FROM google_signin_nonces WHERE id IN ("
                        + "SELECT id FROM google_signin_nonces WHERE expires_at < now() LIMIT "
                        + CLEANUP_BATCH + ")");
        return new IssuedNonce(nonce, properties.nonceTtlSeconds());
    }

    /**
     * Marks {@code nonce} consumed for {@code purpose}. Returns {@code false}
     * for a malformed, unknown, wrong-purpose, expired or already-consumed
     * value — the caller must fail closed.
     *
     * <p>{@code REQUIRES_NEW}: the consumption must survive the caller's
     * rollback, otherwise a failed sign-in (for example an unlinked account)
     * would release the nonce for replay.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(String nonce, String purpose, java.util.UUID userId) {
        if (nonce == null || nonce.isBlank() || nonce.length() > MAX_NONCE_LENGTH) {
            return false;
        }
        int updated;
        if (userId == null) {
            // Public sign-in nonces have no owner.
            updated = jdbcTemplate().update(
                    "UPDATE google_signin_nonces SET consumed_at = now()"
                            + " WHERE nonce_hash = ? AND purpose = ? AND user_id IS NULL"
                            + " AND consumed_at IS NULL AND expires_at > now()",
                    sha256Hex(nonce), purpose);
        } else {
            updated = jdbcTemplate().update(
                    "UPDATE google_signin_nonces SET consumed_at = now()"
                            + " WHERE nonce_hash = ? AND purpose = ? AND user_id = ?"
                            + " AND consumed_at IS NULL AND expires_at > now()",
                    sha256Hex(nonce), purpose, userId);
        }
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
