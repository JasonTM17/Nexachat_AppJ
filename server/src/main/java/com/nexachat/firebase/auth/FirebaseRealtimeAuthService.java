package com.nexachat.firebase.auth;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.firebase.config.FirebaseDatabaseUrl;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/**
 * Freshly validates Nexa JWT state before authorizing Firebase bridge changes.
 * Transactions lock the shared user row through provider writes so issue and
 * renewal are ordered with the transaction that advances the auth cutoff.
 */
public class FirebaseRealtimeAuthService {

    static final long BRIDGE_LEASE_SECONDS = 120;
    private static final String TRANSPORT = "FIREBASE";

    private final UserRepository users;
    private final ConversationMemberRepository members;
    private final FirebaseAdminClient admin;
    private final FirebaseRtdbClient rtdb;
    private final FirebaseBridgeLock locks;
    private final FirebaseDatabaseUrl databaseUrl;
    private final Clock clock;

    public FirebaseRealtimeAuthService(UserRepository users,
                                       ConversationMemberRepository members,
                                       FirebaseAdminClient admin,
                                       FirebaseRtdbClient rtdb,
                                       FirebaseBridgeLock locks,
                                       FirebaseDatabaseUrl databaseUrl,
                                       Clock clock) {
        this.users = users;
        this.members = members;
        this.admin = admin;
        this.rtdb = rtdb;
        this.locks = locks;
        this.databaseUrl = databaseUrl;
        this.clock = clock;
    }

    @Transactional
    public FirebaseTokenResponse issue(JwtPrincipal principal) {
        UUID userId = requireUserId(principal);
        return locks.withUserLock(userId, () -> {
            validateFreshAccess(principal);
            UUID bridgeId = UUID.randomUUID();
            String customToken = admin.createCustomToken(userId.toString(), Map.of("bridgeId", bridgeId.toString()));
            Instant leaseUntil = leaseUntil(principal);
            rtdb.writeSessionLease(userId, bridgeId, leaseUntil.toEpochMilli(), Map.of());
            return new FirebaseTokenResponse(customToken, databaseUrl.value(), bridgeId, leaseUntil, TRANSPORT);
        });
    }

    @Transactional
    public FirebaseLeaseResponse renew(JwtPrincipal principal, FirebaseLeaseRequest request) {
        UUID userId = requireUserId(principal);
        validateRequest(request);
        return locks.withUserLock(userId, () -> {
            Instant now = validateFreshAccess(principal);
            Long existingUntil = rtdb.readSessionLease(userId, request.bridgeId());
            if (existingUntil == null || existingUntil <= now.toEpochMilli()) {
                throw invalidBridge();
            }
            for (UUID conversationId : request.conversationIds()) {
                if (!members.existsByConversation_IdAndUser_Id(conversationId, userId)) {
                    throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Current conversation membership is required");
                }
            }
            Instant leaseUntil = leaseUntil(principal);
            Map<UUID, Long> conversationLeases = new HashMap<>();
            for (UUID conversationId : request.conversationIds()) {
                conversationLeases.put(conversationId, leaseUntil.toEpochMilli());
            }
            rtdb.writeSessionLease(userId, request.bridgeId(), leaseUntil.toEpochMilli(), conversationLeases);
            return new FirebaseLeaseResponse(leaseUntil, TRANSPORT);
        });
    }

    @Transactional
    public void revokeBridge(JwtPrincipal principal, UUID bridgeId) {
        UUID userId = requireUserId(principal);
        if (bridgeId == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Bridge id is required");
        }
        locks.withUserLock(userId, () -> {
            validateFreshAccess(principal);
            rtdb.deleteBridge(userId, bridgeId);
        });
    }

    Instant validateFreshAccess(JwtPrincipal principal) {
        UUID userId = requireUserId(principal);
        Instant now = clock.instant();
        if (principal.issuedAt() == null || principal.expiresAt() == null) {
            throw invalidAccessToken();
        }
        if (!principal.expiresAt().isAfter(now)) {
            throw ApiException.of(ApiErrorCode.TOKEN_EXPIRED, "Authentication token has expired");
        }
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(FirebaseRealtimeAuthService::invalidAccessToken);
        if (user.getDeletedAt() != null
                || (user.getTokensValidAfter() != null
                && !principal.issuedAt().isAfter(user.getTokensValidAfter()))) {
            throw invalidAccessToken();
        }
        return now;
    }

    private Instant leaseUntil(JwtPrincipal principal) {
        Instant now = clock.instant();
        if (principal.expiresAt() == null || !principal.expiresAt().isAfter(now)) {
            throw ApiException.of(ApiErrorCode.TOKEN_EXPIRED, "Authentication token has expired");
        }
        Instant cap = now.plusSeconds(BRIDGE_LEASE_SECONDS);
        return cap.isBefore(principal.expiresAt()) ? cap : principal.expiresAt();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }

    private static void validateRequest(FirebaseLeaseRequest request) {
        if (request == null || request.bridgeId() == null || request.conversationIds() == null
                || request.conversationIds().size() > 30
                || request.conversationIds().stream().anyMatch(id -> id == null)
                || new HashSet<>(request.conversationIds()).size() != request.conversationIds().size()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Invalid Firebase lease request");
        }
    }

    private static ApiException invalidAccessToken() {
        return ApiException.of(ApiErrorCode.TOKEN_INVALID, "Authentication token is not valid");
    }

    private static ApiException invalidBridge() {
        return ApiException.of(ApiErrorCode.TOKEN_INVALID, "Firebase bridge session is not active");
    }
}
