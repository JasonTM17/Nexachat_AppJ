package com.nexachat.firebase.auth;

import com.nexachat.auth.AccessTokensRevokedEvent;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.firebase.projection.FirebaseProjectionRetryQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/** Best-effort remote cleanup after the durable Nexa access-token cutoff commits. */
public final class FirebaseSessionRevocationListener {

    private static final Logger log = LoggerFactory.getLogger(FirebaseSessionRevocationListener.class);

    private final FirebaseBridgeLock locks;
    private final FirebaseRtdbClient rtdb;
    private final FirebaseAdminClient admin;
    private final ConversationMemberRepository members;
    private final FirebaseProjectionRetryQueue retries;

    public FirebaseSessionRevocationListener(FirebaseBridgeLock locks,
                                             FirebaseRtdbClient rtdb,
                                             FirebaseAdminClient admin,
                                             ConversationMemberRepository members,
                                             FirebaseProjectionRetryQueue retries) {
        this.locks = locks;
        this.rtdb = rtdb;
        this.admin = admin;
        this.members = members;
        this.retries = retries;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccessTokensRevoked(AccessTokensRevokedEvent event) {
        UUID userId = event.userId();
        try {
            locks.withUserLock(userId, () -> revoke(userId));
        } catch (RuntimeException failure) {
            log.warn("Firebase realtime session revocation failed ({})", failure.getClass().getSimpleName());
            boolean queued = retries.enqueue("session-revocation:" + userId,
                    () -> locks.withUserLock(userId, () -> revoke(userId)));
            if (!queued) {
                log.error("Firebase realtime session revocation could not be queued");
            }
        }
    }

    private void revoke(UUID userId) {
        List<UUID> conversationIds = members.findConversationIdsByUserId(userId);
        rtdb.revokeUser(userId, conversationIds);
        admin.revokeRefreshTokens(userId.toString());
    }
}
