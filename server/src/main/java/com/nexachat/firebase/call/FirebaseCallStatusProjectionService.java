package com.nexachat.firebase.call;

import com.nexachat.call.CallService;
import com.nexachat.call.CallSession;
import com.nexachat.call.CallState;
import com.nexachat.call.CallStatusProjectionEvent;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Publishes bounded display-only call status without moving call signaling authority. */
public class FirebaseCallStatusProjectionService {

    static final long ACTIVE_LEASE_MILLIS = 120_000L;
    static final long ACTIVE_RENEW_INTERVAL_MILLIS = 60_000L;
    static final long ENDED_TTL_MILLIS = 60_000L;
    static final int MAX_PATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(FirebaseCallStatusProjectionService.class);

    private final FirebaseRtdbClient rtdb;
    private final CallService callService;
    private final Clock clock;
    private final long ringTimeoutMillis;

    public FirebaseCallStatusProjectionService(FirebaseRtdbClient rtdb,
                                               CallService callService,
                                               Clock clock,
                                               long ringTimeoutMillis) {
        if (ringTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Call ring timeout must be positive");
        }
        this.rtdb = rtdb;
        this.callService = callService;
        this.clock = clock;
        this.ringTimeoutMillis = ringTimeoutMillis;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallStatusChanged(CallStatusProjectionEvent event) {
        try {
            projectTransition(event.session());
        } catch (RuntimeException failure) {
            log.warn("Firebase call-status projection failed; server call signaling remains authoritative");
        }
    }

    /** Renews only ACTIVE calls held by this process; after a restart the old lease expires by itself. */
    @Scheduled(fixedRate = ACTIVE_RENEW_INTERVAL_MILLIS)
    public void renewActiveCallProjections() {
        callService.withActiveCallSessions(sessions -> {
            for (int start = 0; start < sessions.size(); start += MAX_PATCH_SIZE) {
                int end = Math.min(start + MAX_PATCH_SIZE, sessions.size());
                Instant now = clock.instant();
                Map<String, Object> updates = new LinkedHashMap<>();
                for (CallSession session : sessions.subList(start, end)) {
                    updates.put("calls/" + session.callId(),
                            status(session, now, now.plusMillis(ACTIVE_LEASE_MILLIS)));
                }
                try {
                    rtdb.applyCallStatusProjection(updates);
                } catch (RuntimeException failure) {
                    log.warn("Firebase call-status lease renewal failed; server call signaling remains authoritative");
                }
            }
        });
    }

    private void projectTransition(CallSession session) {
        Instant updatedAt;
        Instant expiresAt;
        if (session.state() == CallState.RINGING) {
            updatedAt = session.startedAt();
            expiresAt = updatedAt.plusMillis(ringTimeoutMillis);
        } else if (session.state() == CallState.ACTIVE) {
            updatedAt = session.answeredAt();
            expiresAt = updatedAt.plusMillis(ACTIVE_LEASE_MILLIS);
        } else {
            updatedAt = session.endedAt();
            expiresAt = updatedAt.plusMillis(ENDED_TTL_MILLIS);
        }
        write(session, updatedAt, expiresAt);
    }

    private void write(CallSession session, Instant updatedAt, Instant expiresAt) {
        String path = "calls/" + session.callId();
        Map<String, Object> updates = new LinkedHashMap<>();
        if (!expiresAt.isAfter(clock.instant())) {
            updates.put(path, null);
        } else {
            updates.put(path, status(session, updatedAt, expiresAt));
        }
        rtdb.applyCallStatusProjection(updates);
    }

    private static Map<String, Object> status(CallSession session, Instant updatedAt, Instant expiresAt) {
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("cid", session.conversationId().toString());
        projection.put("callerId", session.callerId().toString());
        projection.put("calleeId", session.calleeId().toString());
        projection.put("state", session.state().name());
        projection.put("updatedAt", updatedAt.toEpochMilli());
        projection.put("expiresAt", expiresAt.toEpochMilli());
        return projection;
    }
}
