package com.nexachat.firebase.auth;

import com.nexachat.auth.AccessTokensRevokedEvent;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.firebase.client.FirebaseRtdbClient;
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

    public FirebaseSessionRevocationListener(FirebaseBridgeLock locks,
                                             FirebaseRtdbClient rtdb,
                                             FirebaseAdminClient admin,
                                             ConversationMemberRepository members) {
        this.locks = locks;
        this.rtdb = rtdb;
        this.admin = admin;
        this.members = members;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccessTokensRevoked(AccessTokensRevokedEvent event) {
        UUID userId = event.userId();
        locks.withUserLock(userId, () -> revoke(userId));
    }

    private void revoke(UUID userId) {
        List<UUID> conversationIds;
        try {
            conversationIds = members.findConversationIdsByUserId(userId);
        } catch (RuntimeException ex) {
            conversationIds = List.of();
            log.warn("Firebase realtime membership cleanup lookup failed ({})", ex.getClass().getSimpleName());
        }

        try {
            rtdb.revokeUser(userId, conversationIds);
        } catch (RuntimeException ex) {
            log.warn("Firebase realtime grant cleanup failed ({})", ex.getClass().getSimpleName());
        }

        try {
            admin.revokeRefreshTokens(userId.toString());
        } catch (RuntimeException ex) {
            log.warn("Firebase Auth token revocation failed ({})", ex.getClass().getSimpleName());
        }
    }
}
