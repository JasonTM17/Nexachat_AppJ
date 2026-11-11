package com.nexachat.call.ui;

import com.nexachat.BuildConfig;
import com.google.firebase.database.DataSnapshot;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;

/**
 * Converts an authenticated Firebase call-status snapshot into passive display data.
 * It never changes CallManager, signaling, or call controls.
 */
public final class FirebaseCallStatusDisplayAdapter {

    private static final Set<String> REQUIRED_FIELDS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "cid", "callerId", "calleeId", "state", "updatedAt", "expiresAt")));

    private final boolean enabled;

    @Inject
    public FirebaseCallStatusDisplayAdapter() {
        this(BuildConfig.FIREBASE_CALL_STATUS_ENABLED);
    }

    public FirebaseCallStatusDisplayAdapter(boolean enabled) {
        this.enabled = enabled;
    }

    public Optional<CallStatusDisplay> adapt(
            String snapshotCallId,
            String expectedCallId,
            String viewerId,
            Map<?, ?> payload,
            long nowMillis) {
        if (!enabled || !isUuid(snapshotCallId) || !isUuid(expectedCallId)
                || !snapshotCallId.equalsIgnoreCase(expectedCallId) || !isUuid(viewerId)
                || payload == null || !payload.keySet().equals(REQUIRED_FIELDS)) {
            return Optional.empty();
        }
        String conversationId = uuid(payload.get("cid"));
        String callerId = uuid(payload.get("callerId"));
        String calleeId = uuid(payload.get("calleeId"));
        if (conversationId == null || callerId == null || calleeId == null || callerId.equals(calleeId)) {
            return Optional.empty();
        }
        boolean viewerIsCaller = viewerId.equals(callerId);
        if (!viewerIsCaller && !viewerId.equals(calleeId)) {
            return Optional.empty();
        }
        State state = state(payload.get("state"));
        Long updatedAt = epochMillis(payload.get("updatedAt"));
        Long expiresAt = epochMillis(payload.get("expiresAt"));
        if (state == null || updatedAt == null || expiresAt == null
                || updatedAt < 0 || expiresAt <= nowMillis || expiresAt <= updatedAt
                || expiresAt - updatedAt > state.maximumTtlMillis) {
            return Optional.empty();
        }
        return Optional.of(new CallStatusDisplay(
                expectedCallId,
                conversationId,
                viewerIsCaller ? calleeId : callerId,
                state,
                updatedAt,
                expiresAt,
                viewerIsCaller));
    }

    public Optional<CallStatusDisplay> adapt(
            DataSnapshot snapshot,
            String expectedCallId,
            String viewerId,
            long nowMillis) {
        if (snapshot == null || !(snapshot.getValue() instanceof Map<?, ?> payload)) {
            return Optional.empty();
        }
        return adapt(snapshot.getKey(), expectedCallId, viewerId, payload, nowMillis);
    }

    private static State state(Object value) {
        if (!(value instanceof String state)) return null;
        try {
            return State.valueOf(state);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Long epochMillis(Object value) {
        if (!(value instanceof Long || value instanceof Integer)) return null;
        return ((Number) value).longValue();
    }

    private static String uuid(Object value) {
        if (!(value instanceof String id) || !isUuid(id)) return null;
        return id.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public enum State {
        RINGING(45_000L),
        ACTIVE(120_000L),
        ENDED(60_000L);

        private final long maximumTtlMillis;

        State(long maximumTtlMillis) {
            this.maximumTtlMillis = maximumTtlMillis;
        }
    }

    /** Presentation-only status; it contains no SDP, ICE, or call-control actions. */
    public static final class CallStatusDisplay {
        private final String callId;
        private final String conversationId;
        private final String peerUserId;
        private final State state;
        private final long updatedAtMillis;
        private final long expiresAtMillis;
        private final boolean viewerCaller;

        private CallStatusDisplay(String callId, String conversationId, String peerUserId,
                                  State state, long updatedAtMillis, long expiresAtMillis,
                                  boolean viewerCaller) {
            this.callId = callId;
            this.conversationId = conversationId;
            this.peerUserId = peerUserId;
            this.state = state;
            this.updatedAtMillis = updatedAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.viewerCaller = viewerCaller;
        }

        public String getCallId() { return callId; }
        public String getConversationId() { return conversationId; }
        public String getPeerUserId() { return peerUserId; }
        public State getState() { return state; }
        public long getUpdatedAtMillis() { return updatedAtMillis; }
        public long getExpiresAtMillis() { return expiresAtMillis; }
        public boolean isViewerCaller() { return viewerCaller; }
    }
}
