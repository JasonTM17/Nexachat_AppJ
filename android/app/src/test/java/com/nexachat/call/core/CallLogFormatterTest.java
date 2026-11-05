package com.nexachat.call.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class CallLogFormatterTest {
    @Test
    public void completionIncludesLocalizedKeyAndDurationArgument() {
        CallLogFormatter.FormattedCallLog formatted = CallLogFormatter.fromSystemMessage(
                "CALL|COMPLETED|78",
                CallSession.Direction.OUTGOING);

        assertEquals(CallLogFormatter.OUTGOING_COMPLETED_KEY, formatted.getResourceKey());
        assertEquals("1:18", formatted.getArgs()[0]);
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
    }
}
