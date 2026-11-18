package com.nexachat.call.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;

public final class CallTerminalSignalTest {

    @Test
    public void serverInitiatedTerminalNeverEchoesASignalBack() {
        for (CallState state : CallState.values()) {
            assertEquals(CallTerminalSignal.NONE,
                    CallTerminalSignal.forLocalFailure(state, true));
        }
        assertEquals(CallTerminalSignal.NONE, CallTerminalSignal.forLocalFailure(null, true));
    }

    @Test
    public void localFailureMappingCoversEveryCallState() {
        Map<CallState, CallTerminalSignal> expected = new EnumMap<>(CallState.class);
        expected.put(CallState.OUTGOING_RINGING, CallTerminalSignal.CANCEL);
        expected.put(CallState.INCOMING_RINGING, CallTerminalSignal.REJECT);
        expected.put(CallState.PENDING_INCOMING, CallTerminalSignal.REJECT);
        expected.put(CallState.CONNECTING, CallTerminalSignal.END);
        expected.put(CallState.ACTIVE, CallTerminalSignal.END);
        expected.put(CallState.RECONNECTING_NETWORK, CallTerminalSignal.END);
        expected.put(CallState.IDLE, CallTerminalSignal.NONE);
        expected.put(CallState.ENDED, CallTerminalSignal.NONE);
        // A new CallState fails here until it is mapped explicitly above.
        assertEquals(CallState.values().length, expected.size());
        for (CallState state : CallState.values()) {
            assertEquals("mapping for " + state, expected.get(state),
                    CallTerminalSignal.forLocalFailure(state, false));
        }
        assertEquals(CallTerminalSignal.NONE, CallTerminalSignal.forLocalFailure(null, false));
    }

    @Test
    public void decisionMapsToItsWireSignalType() {
        assertEquals(CallSignal.Type.CANCEL, CallTerminalSignal.CANCEL.signalType());
        assertEquals(CallSignal.Type.REJECT, CallTerminalSignal.REJECT.signalType());
        assertEquals(CallSignal.Type.END, CallTerminalSignal.END.signalType());
        assertNull(CallTerminalSignal.NONE.signalType());
    }
}
