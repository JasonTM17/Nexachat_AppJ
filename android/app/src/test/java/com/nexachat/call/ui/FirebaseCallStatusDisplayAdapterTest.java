package com.nexachat.call.ui;

import com.nexachat.call.ui.FirebaseCallStatusDisplayAdapter.CallStatusDisplay;
import com.nexachat.call.ui.FirebaseCallStatusDisplayAdapter.State;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FirebaseCallStatusDisplayAdapterTest {

    private static final String CALL_ID = "33333333-3333-4333-8333-333333333333";
    private static final String CONVERSATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String CALLER_ID = "11111111-1111-4111-8111-111111111111";
    private static final String CALLEE_ID = "22222222-2222-4222-8222-222222222222";

    @Test
    public void participantGetsAReadOnlyStatusAndPeerIdentity() {
        FirebaseCallStatusDisplayAdapter adapter = new FirebaseCallStatusDisplayAdapter(true);

        Optional<CallStatusDisplay> display = adapter.adapt(
                CALL_ID, CALL_ID, CALLER_ID, projection("ACTIVE", 1_000L, 121_000L), 2_000L);

        assertTrue(display.isPresent());
        assertEquals(State.ACTIVE, display.orElseThrow().getState());
        assertEquals(CALLEE_ID, display.orElseThrow().getPeerUserId());
        assertTrue(display.orElseThrow().isViewerCaller());
        assertEquals(CALL_ID, display.orElseThrow().getCallId());
    }

    @Test
    public void nonParticipantCannotDisplayAnotherCallsProjection() {
        FirebaseCallStatusDisplayAdapter adapter = new FirebaseCallStatusDisplayAdapter(true);

        assertFalse(adapter.adapt(CALL_ID, CALL_ID,
                "44444444-4444-4444-8444-444444444444",
                projection("RINGING", 1_000L, 46_000L), 2_000L).isPresent());
    }

    @Test
    public void disabledOrExpiredProjectionIsNotDisplayed() {
        Map<String, Object> payload = projection("ENDED", 1_000L, 61_000L);

        assertFalse(new FirebaseCallStatusDisplayAdapter(false)
                .adapt(CALL_ID, CALL_ID, CALLER_ID, payload, 2_000L).isPresent());
        assertFalse(new FirebaseCallStatusDisplayAdapter(true)
                .adapt(CALL_ID, CALL_ID, CALLER_ID, payload, 61_000L).isPresent());
    }

    @Test
    public void malformedOrSensitiveProjectionIsRejected() {
        FirebaseCallStatusDisplayAdapter adapter = new FirebaseCallStatusDisplayAdapter(true);
        Map<String, Object> payload = projection("ACTIVE", 1_000L, 121_000L);
        payload.put("candidate", "private media signaling");

        assertFalse(adapter.adapt(CALL_ID, CALL_ID, CALLER_ID, payload, 2_000L).isPresent());
        assertFalse(adapter.adapt("not-a-call-id", CALL_ID, CALLER_ID,
                projection("ACTIVE", 1_000L, 121_000L), 2_000L).isPresent());
        assertFalse(adapter.adapt(CALL_ID, "55555555-5555-4555-8555-555555555555", CALLER_ID,
                projection("ACTIVE", 1_000L, 121_000L), 2_000L).isPresent());
    }

    private static Map<String, Object> projection(String state, long updatedAt, long expiresAt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("cid", CONVERSATION_ID);
        value.put("callerId", CALLER_ID);
        value.put("calleeId", CALLEE_ID);
        value.put("state", state);
        value.put("updatedAt", updatedAt);
        value.put("expiresAt", expiresAt);
        return value;
    }
}
