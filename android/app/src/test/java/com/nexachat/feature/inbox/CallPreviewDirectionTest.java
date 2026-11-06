package com.nexachat.feature.inbox;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CallPreviewDirectionTest {
    @Test
    public void matchingCallerIdIsOutgoing() {
        assertTrue(CallPreviewDirection.isOutgoing("CALL|COMPLETED|18|user-1", "user-1"));
    }

    @Test
    public void otherCallerAndLegacyTokenAreIncoming() {
        assertFalse(CallPreviewDirection.isOutgoing("CALL|COMPLETED|18|user-2", "user-1"));
        assertFalse(CallPreviewDirection.isOutgoing("CALL|COMPLETED|18", "user-1"));
    }

    @Test
    public void missingIdentityOrMalformedTokenIsIncoming() {
        assertFalse(CallPreviewDirection.isOutgoing("CALL|COMPLETED|18|user-1", null));
        assertFalse(CallPreviewDirection.isOutgoing("CALL|COMPLETED|18|", "user-1"));
        assertFalse(CallPreviewDirection.isOutgoing("CALL|COMPLETED|18|   ", "user-1"));
        assertFalse(CallPreviewDirection.isOutgoing("OTHER|COMPLETED|18|user-1", "user-1"));
    }
}
