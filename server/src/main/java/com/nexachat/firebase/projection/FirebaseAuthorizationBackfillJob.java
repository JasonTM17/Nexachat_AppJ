package com.nexachat.firebase.projection;

import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded-keyset account and direct-reader reconciliation after server startup. */
public class FirebaseAuthorizationBackfillJob {

    static final int PAGE_SIZE = 200;
    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthorizationBackfillJob.class);

    private final UserRepository users;
    private final ConversationRepository conversations;
    private final FirebaseAuthorizationProjectionService projections;
    private final FirebaseRtdbClient rtdb;
    private final FirebaseProjectionMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean();

    public FirebaseAuthorizationBackfillJob(UserRepository users,
                                            ConversationRepository conversations,
                                            FirebaseAuthorizationProjectionService projections,
                                            FirebaseRtdbClient rtdb,
                                            FirebaseProjectionMetrics metrics) {
        this.users = users;
        this.conversations = conversations;
        this.projections = projections;
        this.rtdb = rtdb;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void closeAdmissionUntilReconciled() {
        try {
            metrics.write("control", () -> rtdb.applyAuthorizationProjection(Map.of("control/enabled", false)));
        } catch (RuntimeException failure) {
            log.warn("Firebase authorization admission could not be closed at startup ({})",
                    failure.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${nexachat.firebase.projection.backfill-interval-ms:3600000}",
            initialDelayString = "${nexachat.firebase.projection.backfill-initial-delay-ms:10000}")
    public void scheduledBackfill() {
        try {
            backfill();
        } catch (RuntimeException failure) {
            log.warn("Firebase authorization backfill did not complete ({})",
                    failure.getClass().getSimpleName());
        }
    }

    /** Exposed for deterministic fake-client tests and controlled operator runs. */
    public void backfill() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            rtdb.applyAuthorizationProjection(Map.of("control/enabled", false));
            UUID afterUserId = null;
            while (true) {
                var page = users.findIdsAfter(afterUserId, PageRequest.of(0, PAGE_SIZE));
                if (page.isEmpty()) {
                    break;
                }
                for (UUID userId : page) {
                    projections.refreshAccount(userId);
                }
                metrics.backfillRows("accounts", page.size());
                afterUserId = page.get(page.size() - 1);
            }

            UUID afterConversationId = null;
            while (true) {
                var page = conversations.findIdsAfterByType(
                        afterConversationId, Conversation.Type.DIRECT, PageRequest.of(0, PAGE_SIZE));
                if (page.isEmpty()) {
                    break;
                }
                for (UUID conversationId : page) {
                    projections.reconcileConversation(conversationId);
                }
                metrics.backfillRows("direct-readers", page.size());
                afterConversationId = page.get(page.size() - 1);
            }
            metrics.write("control", () -> rtdb.applyAuthorizationProjection(Map.of("control/enabled", true)));
        } finally {
            running.set(false);
        }
    }
}
