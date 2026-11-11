package com.nexachat.firebase.projection;

import com.nexachat.conversation.ConversationRepository;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.UUID;

/** Bounded sweeps for expired RTDB bridge, membership, typing, and presence leases. */
public class FirebaseAuthorizationLeaseSweepJob {

    static final int PAGE_SIZE = 100;
    static final int CHILD_BATCH_SIZE = 100;

    private final UserRepository users;
    private final ConversationRepository conversations;
    private final FirebaseRtdbClient rtdb;
    private final Clock clock;
    private final FirebaseProjectionMetrics metrics;
    private UUID conversationCursor;
    private UUID userCursor;

    public FirebaseAuthorizationLeaseSweepJob(UserRepository users,
                                              ConversationRepository conversations,
                                              FirebaseRtdbClient rtdb,
                                              Clock clock,
                                              FirebaseProjectionMetrics metrics) {
        this.users = users;
        this.conversations = conversations;
        this.rtdb = rtdb;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${nexachat.firebase.projection.lease-sweep-interval-ms:60000}",
            initialDelayString = "${nexachat.firebase.projection.lease-sweep-initial-delay-ms:15000}")
    public void scheduledSweep() {
        sweepOneBatch();
    }

    /** Runs one keyset page, keeping work bounded and safe to repeat after restart. */
    public void sweepOneBatch() {
        long now = clock.millis();
        var conversationPage = conversations.findIdsAfter(conversationCursor, PageRequest.of(0, PAGE_SIZE));
        for (UUID conversationId : conversationPage) {
            metrics.expiredRows("members", rtdb.deleteExpiredChildren(
                    "members/" + conversationId, "$value", now, CHILD_BATCH_SIZE));
            metrics.expiredRows("typing", rtdb.deleteExpiredChildren(
                    "typing/" + conversationId, "expiresAt", now, CHILD_BATCH_SIZE));
            conversationCursor = conversationId;
        }
        if (conversationPage.size() < PAGE_SIZE) {
            conversationCursor = null;
        }

        var userPage = users.findIdsAfter(userCursor, PageRequest.of(0, PAGE_SIZE));
        for (UUID userId : userPage) {
            metrics.expiredRows("sessions", rtdb.deleteExpiredChildren(
                    "sessions/" + userId, "until", now, CHILD_BATCH_SIZE));
            metrics.expiredRows("presence", rtdb.expirePresenceEntries(userId, now, CHILD_BATCH_SIZE));
            userCursor = userId;
        }
        if (userPage.size() < PAGE_SIZE) {
            userCursor = null;
        }
    }
}
