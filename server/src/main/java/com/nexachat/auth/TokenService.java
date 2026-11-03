package com.nexachat.auth;

import com.nexachat.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh-token lifecycle: issue → rotate → revoke, with reuse detection.
 *
 * <ul>
 *   <li>Opaque token = 48 random bytes → base64url → exactly 64 URL-safe chars.</li>
 *   <li>Only {@code sha256(token)} is persisted; the raw value is returned once
 *       and never stored (DB leak ≠ usable token, §13).</li>
 *   <li>Rotation: the old row gets {@code revoked_at} + {@code replaced_by} pointing
 *       at the new row → auditable chain RT1 → RT2 → …</li>
 *   <li>Reuse: presenting a revoked (or expired) token signals theft. The rotation
 *       transaction must ROLL BACK, so this service signals via
 *       {@link RefreshTokenException} and the caller commits the family revocation
 *       in a fresh transaction.</li>
 * </ul>
 */
@Service
public class TokenService {

    /** Result of a successful rotation: the owner and the NEW raw refresh token. */
    public record RotationResult(UUID userId, String refreshToken) {
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int OPAQUE_BYTES = 48; // → 64 base64url chars, no padding
    private static final int DEVICE_LABEL_MAX = 80;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties properties;

    public TokenService(RefreshTokenRepository refreshTokenRepository, JwtProperties properties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
    }

    /** Issues a new refresh-token row for {@code user}; returns the raw opaque value. */
    @Transactional
    public String issue(User user, String deviceLabel) {
        String raw = generateOpaqueToken();
        refreshTokenRepository.save(
                RefreshToken.issue(user, sha256Hex(raw), normalizeDeviceLabel(deviceLabel), freshExpiry()));
        return raw;
    }

    /**
     * Rotates {@code rawToken}: validates, then revokes it and links it to its
     * successor in one transaction.
     *
     * @throws RefreshTokenException when the token is unknown, revoked (reuse) or
     *         expired — the exception carries the user id so the caller can commit
     *         the family revocation in a separate transaction.
     */
    @Transactional
    public RotationResult rotate(String rawToken, String deviceLabel) {
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(sha256Hex(rawToken))
                .orElseThrow(() -> RefreshTokenException.invalid(null));

        UUID userId = token.getUser().getId();
        if (token.isRevoked()) {
            throw RefreshTokenException.reused(userId);
        }
        if (token.isExpiredAt(Instant.now())) {
            throw RefreshTokenException.invalid(userId);
        }

        String newRaw = generateOpaqueToken();
        RefreshToken next = refreshTokenRepository.save(
                RefreshToken.issue(token.getUser(), sha256Hex(newRaw), normalizeDeviceLabel(deviceLabel), freshExpiry()));

        token.revoke(Instant.now());
        token.markReplacedBy(next.getId());
        refreshTokenRepository.save(token);

        return new RotationResult(userId, newRaw);
    }

    /** Idempotent logout: revokes the token only if it belongs to {@code userId}. */
    @Transactional
    public void revokeIfOwned(String rawToken, UUID userId) {
        refreshTokenRepository.findByTokenHash(sha256Hex(rawToken))
                .filter(token -> token.getUser().getId().equals(userId))
                .ifPresent(token -> token.revoke(Instant.now()));
    }

    /** Revokes every live refresh token of the user (logout-all / reuse response / password change). */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.revokeAllActiveByUserId(userId, Instant.now());
    }

    /** Revokes every live refresh token except the one identified by {@code keepRawToken}. */
    @Transactional
    public void revokeAllForUserExcept(UUID userId, String keepRawToken) {
        refreshTokenRepository.revokeAllActiveByUserIdExceptTokenHash(
                userId, sha256Hex(keepRawToken), Instant.now());
    }

    /** sha256 hex — the only representation of a refresh token that touches the DB. */
    static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String generateOpaqueToken() {
        byte[] bytes = new byte[OPAQUE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Instant freshExpiry() {
        return Instant.now().plus(Duration.ofDays(properties.refreshTtlDays()));
    }

    private static String normalizeDeviceLabel(String deviceLabel) {
        if (deviceLabel == null) {
            return null;
        }
        String trimmed = deviceLabel.trim();
        return trimmed.length() > DEVICE_LABEL_MAX ? trimmed.substring(0, DEVICE_LABEL_MAX) : trimmed;
    }
}
