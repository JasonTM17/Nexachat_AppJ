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
        // Five-part tokens are valid since the media-type marker shipped; an
        // unknown marker degrades to audio wording instead of failing.
        assertEquals(CallLogFormatter.OUTGOING_COMPLETED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|COMPLETED|0|caller-id|extra", CallSession.Direction.OUTGOING).getResourceKey());
        assertEquals(CallLogFormatter.FAILED_KEY, CallLogFormatter.fromSystemMessage(
                null, CallSession.Direction.OUTGOING).getResourceKey());
        assertEquals(CallLogFormatter.FAILED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|COMPLETED|not-a-number", CallSession.Direction.OUTGOING).getResourceKey());
    }

    @Test
    public void videoTokensUseVideoKeys() {
        assertEquals(CallLogFormatter.VIDEO_MISSED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|MISSED|0|caller-id|VIDEO", CallSession.Direction.INCOMING).getResourceKey());
        CallLogFormatter.FormattedCallLog completed = CallLogFormatter.fromSystemMessage(
                "CALL|COMPLETED|90|caller-id|VIDEO", CallSession.Direction.OUTGOING);
        assertEquals(CallLogFormatter.VIDEO_OUTGOING_COMPLETED_KEY, completed.getResourceKey());
        assertEquals("1:30", completed.getArgs()[0]);
        assertEquals("caller-id", completed.getCallerUserId());
    }

    @Test
    public void unknownMediaValueAndVideoMediaTypesBehave() {
        // Unknown values still parse to AUDIO; the existing empty-caller rule
        // rejects malformed five-part tokens unchanged.
        assertEquals(CallLogFormatter.MISSED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|MISSED|0|caller-id|3D", CallSession.Direction.INCOMING).getResourceKey());
        assertEquals(CallLogFormatter.FAILED_KEY, CallLogFormatter.fromSystemMessage(
                "CALL|MISSED|0||VIDEO", CallSession.Direction.INCOMING).getResourceKey());
    }
}
