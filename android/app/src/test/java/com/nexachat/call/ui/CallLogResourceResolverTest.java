package com.nexachat.call.ui;

import com.nexachat.R;
import com.nexachat.call.core.CallEndReason;
import com.nexachat.call.core.CallLogFormatter;
import com.nexachat.call.core.CallSession;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CallLogResourceResolverTest {
    @Test public void resolvesEveryFormatterKeyToLocalizedResources() {
        assertEquals(R.string.call_log_missed,
                CallLogResourceResolver.resourceFor(CallLogFormatter.MISSED_KEY));
        assertEquals(R.string.call_log_declined,
                CallLogResourceResolver.resourceFor(CallLogFormatter.DECLINED_KEY));
        assertEquals(R.string.call_log_cancelled,
                CallLogResourceResolver.resourceFor(CallLogFormatter.CANCELLED_KEY));
        assertEquals(R.string.call_log_busy,
                CallLogResourceResolver.resourceFor(CallLogFormatter.BUSY_KEY));
        assertEquals(R.string.call_log_failed,
                CallLogResourceResolver.resourceFor(CallLogFormatter.FAILED_KEY));
        assertEquals(R.string.call_log_outgoing_completed,
                CallLogResourceResolver.resourceFor(CallLogFormatter.OUTGOING_COMPLETED_KEY));
        assertEquals(R.string.call_log_incoming_completed,
                CallLogResourceResolver.resourceFor(CallLogFormatter.INCOMING_COMPLETED_KEY));
    }

    @Test public void completedCallUsesDirectionAwareResource() {
        CallSession session = new CallSession("call", "conversation", "peer", "Peer",
                CallSession.Direction.INCOMING, com.nexachat.call.core.CallState.ENDED,
                CallEndReason.COMPLETED, 1L, 2L, false, false);
        assertEquals(R.string.call_log_incoming_completed,
                CallLogResourceResolver.resourceFor(session, 61L));
    }

    @Test public void serverCallLogMessageResolvesLocalizedTextAndDurationArguments() {
        CallLogResourceResolver.ResolvedCallLog outgoing =
                CallLogResourceResolver.fromSystemMessage("CALL|COMPLETED|61", true);
        assertEquals(R.string.call_log_outgoing_completed, outgoing.getResourceId());
        assertEquals("1:01", outgoing.getArguments()[0]);

        CallLogResourceResolver.ResolvedCallLog incoming =
                CallLogResourceResolver.fromSystemMessage("CALL|COMPLETED|61", false);
        assertEquals(R.string.call_log_incoming_completed, incoming.getResourceId());
        assertEquals("1:01", incoming.getArguments()[0]);
    }

    @Test public void malformedServerCallLogFallsBackToLocalizedFailure() {
        CallLogResourceResolver.ResolvedCallLog result =
                CallLogResourceResolver.fromSystemMessage("CALL|UNKNOWN|oops", true);
        assertEquals(R.string.call_log_failed, result.getResourceId());
        assertEquals(0, result.getArguments().length);
    }
}
