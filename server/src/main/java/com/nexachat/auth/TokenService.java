package com.nexachat.auth;

import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Refresh-token lifecycle serialized by the owner row before any token-row lock. */
@Service
public class TokenService {

    public record RotationResult(UUID userId, UUID predecessorId, AuthResponse response) { }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int OPAQUE_BYTES = 48;
    private static final int DEVICE_LABEL_MAX = 80;
    private static final Duration REPLAY_WINDOW = Duration.ofSeconds(30);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final JwtProperties properties;
    private final Clock clock;
    private final RefreshReplayCipher replayCipher;
    private final AuthResponseFactory responseFactory;
    private final RefreshReplayConfiguration.BoundaryHook boundaryHook;

    public TokenService(RefreshTokenRepository refreshTokenRepository,
                        UserRepository userRepository,
                        EntityManager entityManager,
                        JwtProperties properties,
                        Clock clock,
                        RefreshReplayCipher replayCipher,
                        AuthResponseFactory responseFactory,
                        RefreshReplayConfiguration.BoundaryHook boundaryHook) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.properties = properties;
        this.clock = clock;
        this.replayCipher = replayCipher;
        this.responseFactory = responseFactory;
        this.boundaryHook = boundaryHook;
    }

    /** Issues a new refresh-token row for {@code user}; returns the raw opaque value. */
    @Transactional
    public String issue(User user, String deviceLabel) {
        UUID userId = user.getId();
        User lockedUser = lockUser(userId, RefreshReplayConfiguration.Operation.ISSUE);
        if (lockedUser == null) {
            throw new IllegalStateException("Cannot issue a session for a missing user");
        }
        // issueSession may have loaded this managed user before acquiring the owner lock.
        entityManager.refresh(lockedUser, LockModeType.PESSIMISTIC_WRITE);
        Instant now = databaseTime();
        String raw = generateOpaqueToken();
        refreshTokenRepository.save(RefreshToken.issue(
                lockedUser, sha256Hex(raw), normalizeDeviceLabel(deviceLabel), freshExpiry(now), now));
        return raw;
    }

    /**
     * Rotates a live token, or returns its exact cached response when the same
     * canonical request key retries within the fixed recovery window.
     */
    @Transactional
    public RotationResult rotate(String rawToken, UUID requestKey, String deviceLabel) {
        // A keyed request cannot consume a token if encryption is unavailable.
        if (requestKey != null) {
            try {
                replayCipher.requireKey();
            } catch (RefreshReplayCipher.KeyUnavailableException exception) {
                throw serviceUnavailable();
            }
        }

        String predecessorHash = sha256Hex(rawToken);
        UUID userId = refreshTokenRepository.findUserIdByTokenHash(predecessorHash)
                .orElseThrow(() -> RefreshTokenException.invalid(null));

        User user = lockUser(userId, RefreshReplayConfiguration.Operation.ROTATE);
        if (user == null) {
            throw RefreshTokenException.invalid(null);
        }
        // No token entity is read before the owner lock; this query sees current row state.
        RefreshToken predecessor = refreshTokenRepository.findByTokenHashForUpdate(predecessorHash)
                .orElseThrow(() -> RefreshTokenException.invalid(null));
        if (!predecessor.getUser().getId().equals(userId)) {
            throw RefreshTokenException.invalid(null);
        }

        Instant now = databaseTime();
        String keyDigest = requestKey == null ? null : RefreshReplayCipher.keyDigest(requestKey);
        if (predecessor.isRevoked()) {
            if (keyDigest != null && keyDigest.equals(predecessor.getReplayKeyDigest())) {
                return recover(predecessor, userId, now);
            }
            throw RefreshTokenException.reused(userId);
        }
        if (predecessor.isExpiredAt(now)) {
            throw RefreshTokenException.invalid(userId);
        }

        String successorRaw = generateOpaqueToken();
        Instant replayUntil = requestKey == null ? null : now.plus(REPLAY_WINDOW);
        RefreshToken successor = refreshTokenRepository.saveAndFlush(RefreshToken.issue(
                user, sha256Hex(successorRaw), normalizeDeviceLabel(deviceLabel), freshExpiry(now), now));
        boundaryHook.reached(RefreshReplayConfiguration.Boundary.AFTER_SUCCESSOR_CREATED,
                RefreshReplayConfiguration.Operation.ROTATE, userId, predecessor.getId());

        // JWT creation and response serialization are inside the owner lock and transaction.
        boundaryHook.reached(RefreshReplayConfiguration.Boundary.BEFORE_RESPONSE_CONSTRUCTION,
                RefreshReplayConfiguration.Operation.ROTATE, userId, predecessor.getId());
        AuthResponse response = responseFactory.create(user, successorRaw);
        boundaryHook.reached(RefreshReplayConfiguration.Boundary.AFTER_RESPONSE_CONSTRUCTION,
                RefreshReplayConfiguration.Operation.ROTATE, userId, predecessor.getId());
        RefreshReplayCipher.EncryptedPayload encrypted = null;
        if (requestKey != null) {
            try {
                encrypted = replayCipher.encrypt(response, userId, predecessor.getId(), successor.getId(),
                        keyDigest, now, replayUntil);
            } catch (RefreshReplayCipher.KeyUnavailableException exception) {
                throw serviceUnavailable();
            } catch (RefreshReplayCipher.CipherFailureException exception) {
                throw serviceUnavailable();
            }
        }

        predecessor.revoke(now);
        predecessor.markReplacedBy(successor.getId());
        if (encrypted != null) {
            predecessor.storeReplay(keyDigest, encrypted.nonce(), encrypted.ciphertext(), now, replayUntil);
        }
        refreshTokenRepository.saveAndFlush(predecessor);
        boundaryHook.reached(RefreshReplayConfiguration.Boundary.AFTER_ROTATION_STORED,
                RefreshReplayConfiguration.Operation.ROTATE, userId, predecessor.getId());

        return new RotationResult(userId, predecessor.getId(), response);
    }

    private RotationResult recover(RefreshToken predecessor, UUID userId, Instant now) {
        Instant rotatedAt = predecessor.getReplayRotatedAt();
        Instant replayUntil = predecessor.getReplayUntil();
        UUID successorId = predecessor.getReplacedBy();
        if (predecessor.isReplayInvalidated() || rotatedAt == null || replayUntil == null || successorId == null
                || predecessor.getCreatedAt() == null || now.isBefore(predecessor.getCreatedAt())
                || now.isBefore(rotatedAt) || !now.isBefore(replayUntil)) {
            throw recoveryUnavailable();
        }

        // Scope the lookup by owner so a malformed replaced_by cannot traverse another account.
        RefreshToken successor = refreshTokenRepository.findByIdAndUserIdForUpdate(successorId, userId)
                .orElseThrow(TokenService::recoveryUnavailable);
        if (successor.isRevoked() || successor.getReplacedBy() != null || successor.isExpiredAt(now)) {
            throw recoveryUnavailable();
        }

        Optional<AuthResponse> decoded = replayCipher.decrypt(
                predecessor.getReplayCiphertext(), predecessor.getReplayNonce(), userId, predecessor.getId(),
                successorId, predecessor.getReplayKeyDigest(), rotatedAt, replayUntil);
        AuthResponse response = decoded.orElseThrow(TokenService::recoveryUnavailable);
        if (response.user() == null || !userId.equals(response.user().id())
                || response.refreshToken() == null
                || !MessageDigest.isEqual(
                        sha256Hex(response.refreshToken()).getBytes(StandardCharsets.US_ASCII),
                        successor.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
            throw recoveryUnavailable();
        }
        return new RotationResult(userId, predecessor.getId(), response);
    }

    /** Revokes only the presented session and its owner-scoped descendant chain. */
    @Transactional
    public void revokeIfOwned(String rawToken, UUID userId) {
        String tokenHash = sha256Hex(rawToken);
        if (refreshTokenRepository.findUserIdByTokenHash(tokenHash).filter(userId::equals).isEmpty()) {
            return;
        }
        User user = lockUser(userId, RefreshReplayConfiguration.Operation.LOGOUT);
        if (user == null) {
            return;
        }
        List<RefreshToken> ownedTokens = refreshTokenRepository.findAllByUserIdForUpdate(userId);
        Map<UUID, RefreshToken> byId = indexById(ownedTokens);
        RefreshToken start = ownedTokens.stream()
                .filter(token -> tokenHash.equals(token.getTokenHash()))
                .findFirst().orElse(null);
        if (start == null) {
            return;
        }
        Instant now = databaseTime();
        for (RefreshToken token : lineage(start, byId)) {
            token.revoke(now);
            token.invalidateReplay();
        }
    }

    /** Revokes all live refresh tokens of the user under the owner lock. */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        User user = lockUser(userId, RefreshReplayConfiguration.Operation.LOGOUT_ALL);
        if (user == null) {
            return;
        }
        revokeAllForLockedUser(userId, databaseTime(), null);
    }

    @Transactional
    public void revokeAllForUserAt(UUID userId, Instant now, RefreshReplayConfiguration.Operation operation) {
        User user = lockUser(userId, operation);
        if (user != null) {
            revokeAllForLockedUser(userId, now, null);
        }
    }

    /** Revokes all other sessions, resolving a known old predecessor to its live session tip. */
    @Transactional
    public void revokeAllForUserExcept(UUID userId, String keepRawToken) {
        User user = lockUser(userId, RefreshReplayConfiguration.Operation.PASSWORD_CHANGE);
        if (user == null) {
            return;
        }
        List<RefreshToken> ownedTokens = refreshTokenRepository.findAllByUserIdForUpdate(userId);
        Instant now = databaseTime();
        String keepHash = liveDescendantHash(ownedTokens, userId, keepRawToken, now);
        revokeAllForLockedUser(userId, now, keepHash);
    }

    /** Invalidates all cached responses at an access-token revocation boundary. */
    @Transactional
    public void invalidateReplayPayloadsForUser(UUID userId) {
        if (userRepository.findByIdForUpdate(userId).isEmpty()) {
            return;
        }
        refreshTokenRepository.invalidateReplayPayloadsByUserId(userId);
    }

    private void revokeAllForLockedUser(UUID userId, Instant now, String keepHash) {
        if (keepHash == null) {
            refreshTokenRepository.revokeAllActiveByUserId(userId, now);
        } else {
            refreshTokenRepository.revokeAllActiveByUserIdExceptTokenHash(userId, keepHash, now);
        }
        refreshTokenRepository.invalidateReplayPayloadsByUserId(userId);
    }

    private String liveDescendantHash(List<RefreshToken> ownedTokens, UUID userId, String rawToken, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String requestedHash = sha256Hex(rawToken);
        Map<UUID, RefreshToken> byId = indexById(ownedTokens);
        RefreshToken start = ownedTokens.stream()
                .filter(token -> requestedHash.equals(token.getTokenHash()))
                .findFirst().orElse(null);
        if (start == null || !userId.equals(start.getUser().getId())) {
            return null;
        }
        Set<UUID> visited = new HashSet<>();
        RefreshToken current = start;
        while (current != null && visited.add(current.getId())) {
            UUID nextId = current.getReplacedBy();
            if (nextId == null) {
                return !current.isRevoked() && !current.isExpiredAt(now) ? current.getTokenHash() : null;
            }
            current = byId.get(nextId);
        }
        return null;
    }

    private static List<RefreshToken> lineage(RefreshToken start, Map<UUID, RefreshToken> byId) {
        List<RefreshToken> chain = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        RefreshToken current = start;
        while (current != null && visited.add(current.getId())) {
            chain.add(current);
            UUID nextId = current.getReplacedBy();
            // The map contains only this owner's rows; missing and foreign links stop traversal.
            current = nextId == null ? null : byId.get(nextId);
        }
        return chain;
    }

    private static Map<UUID, RefreshToken> indexById(List<RefreshToken> tokens) {
        Map<UUID, RefreshToken> byId = new HashMap<>();
        for (RefreshToken token : tokens) {
            byId.put(token.getId(), token);
        }
        return byId;
    }

    private User lockUser(UUID userId, RefreshReplayConfiguration.Operation operation) {
        boundaryHook.reached(RefreshReplayConfiguration.Boundary.BEFORE_USER_LOCK, operation, userId, null);
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user != null) {
            boundaryHook.reached(RefreshReplayConfiguration.Boundary.AFTER_USER_LOCK, operation, userId, null);
        }
        return user;
    }

    /** SHA-256 hex — the only representation of an opaque refresh token persisted in the database. */
    static String sha256Hex(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String generateOpaqueToken() {
        byte[] bytes = new byte[OPAQUE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Instant freshExpiry(Instant now) {
        return now.plus(Duration.ofDays(properties.refreshTtlDays()));
    }

    private Instant databaseTime() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String normalizeDeviceLabel(String deviceLabel) {
        if (deviceLabel == null) {
            return null;
        }
        String trimmed = deviceLabel.trim();
        return trimmed.length() > DEVICE_LABEL_MAX ? trimmed.substring(0, DEVICE_LABEL_MAX) : trimmed;
    }

    private static ApiException recoveryUnavailable() {
        return ApiException.of(ApiErrorCode.REFRESH_RECOVERY_UNAVAILABLE,
                "Refresh response recovery is no longer available");
    }

    private static ApiException serviceUnavailable() {
        return ApiException.of(ApiErrorCode.REFRESH_SERVICE_UNAVAILABLE,
                "Refresh recovery service is unavailable");
    }
}
