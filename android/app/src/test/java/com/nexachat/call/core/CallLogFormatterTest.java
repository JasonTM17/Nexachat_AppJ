package com.nexachat.call.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CallLogFormatterTest {
    @Test
    public void completionIncludesLocalizedKeyAndDurationArgument() {
        CallLogFormatter.FormattedCallLog formatted = CallLogFormatter.fromSystemMessage(
                "CALL|COMPLETED|78|caller-id",
                CallSession.Direction.OUTGOING);

        assertEquals(CallLogFormatter.OUTGOING_COMPLETED_KEY, formatted.getResourceKey());
        assertEquals("1:18", formatted.getArgs()[0]);
        assertEquals("caller-id", formatted.getCallerUserId());
    }

    @Test
    public void legacyCompletionUsesDirectionNeutralKeyAndDuration() {
        for (CallSession.Direction direction : new CallSession.Direction[] {
                CallSession.Direction.OUTGOING,
                CallSession.Direction.INCOMING
        }) {
            CallLogFormatter.FormattedCallLog formatted = CallLogFormatter.fromSystemMessage(
                    "CALL|COMPLETED|78", direction);

            assertEquals("call_log_neutral_completed", formatted.getResourceKey());
            assertEquals("1:18", formatted.getArgs()[0]);
            assertEquals(null, formatted.getCallerUserId());
        }
    }

    @Test
    public void fourPartTokenExposesCallerAndPreservesReasonAndDuration() {
        CallLogFormatter.FormattedCallLog formatted = CallLogFormatter.fromSystemMessage(
                "CALL|COMPLETED|78|caller-id",
                CallSession.Direction.INCOMING);

        assertEquals(CallLogFormatter.INCOMING_COMPLETED_KEY, formatted.getResourceKey());
        assertEquals("1:18", formatted.getArgs()[0]);
        assertEquals("caller-id", formatted.getCallerUserId());
    }

    @Test
    public void outgoingFourPartTokenKeepsOutgoingWording() {
        CallLogFormatter.FormattedCallLog formatted = CallLogFormatter.fromSystemMessage(
                "CALL|COMPLETED|78|caller-id",
                CallSession.Direction.OUTGOING);

        assertEquals(CallLogFormatter.OUTGOING_COMPLETED_KEY, formatted.getResourceKey());
        assertEquals("1:18", formatted.getArgs()[0]);
        assertEquals("caller-id", formatted.getCallerUserId());
    }

    @Test
    public void terminalOutcomesMapToLocalizedKeys() {
        assertEquals(CallLogFormatter.MISSED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|MISSED|0", CallSession.Direction.INCOMING).getResourceKey());
        assertEquals(CallLogFormatter.DECLINED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|REJECTED|0", CallSession.Direction.OUTGOING).getResourceKey());
        assertEquals(CallLogFormatter.BUSY_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|BUSY|0", CallSession.Direction.OUTGOING).getResourceKey());
        assertEquals(CallLogFormatter.FAILED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|BROKEN|0", CallSession.Direction.OUTGOING).getResourceKey());
        assertEquals(CallLogFormatter.FAILED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|COMPLETED|0|", CallSession.Direction.OUTGOING).getResourceKey());
        assertEquals(CallLogFormatter.FAILED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|COMPLETED|0|   ", CallSession.Direction.OUTGOING).getResourceKey());
        assertEquals(CallLogFormatter.FAILED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|COMPLETED|0|caller-id|extra", CallSession.Direction.OUTGOING).getResourceKey());
        assertEquals(CallLogFormatter.FAILED_KEY, CallLogFormatter.fromSystemMessage(
                null, CallSession.Direction.OUTGOING).getResourceKey());
        assertEquals(CallLogFormatter.FAILED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|COMPLETED|not-a-number", CallSession.Direction.OUTGOING).getResourceKey());
    }
}
