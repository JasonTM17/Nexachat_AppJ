package com.nexachat.call.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CallPermissionHelperTest {
    @Test public void microphoneFlowRequestsOrExplainsDenial() {
        assertEquals(CallPermissionHelper.Decision.GRANTED,
                CallPermissionHelper.microphoneDecision(true, false));
        assertEquals(CallPermissionHelper.Decision.REQUEST,
                CallPermissionHelper.microphoneDecision(false, false));
        assertEquals(CallPermissionHelper.Decision.SHOW_RATIONALE,
                CallPermissionHelper.microphoneDecision(false, true));
    }

    @Test public void notificationPermissionIsOptionalBeforeApi33() {
        assertEquals(CallPermissionHelper.Decision.GRANTED,
                CallPermissionHelper.notificationDecision(26, false));
        assertEquals(CallPermissionHelper.Decision.GRANTED,
                CallPermissionHelper.notificationDecision(35, true));
        assertEquals(CallPermissionHelper.Decision.REQUEST,
                CallPermissionHelper.notificationDecision(33, false));
        assertEquals(CallPermissionHelper.Decision.SHOW_RATIONALE,
                CallPermissionHelper.notificationDecision(35, false, true));
        assertEquals(CallPermissionHelper.Decision.REQUEST,
                CallPermissionHelper.notificationDecision(35, false, false));
    }

    @Test public void phoneFallbackRequiresARealNumber() {
        assertTrue(CallPermissionHelper.hasPhoneFallback("+84900000000"));
        assertFalse(CallPermissionHelper.hasPhoneFallback(null));
        assertFalse(CallPermissionHelper.hasPhoneFallback("  "));
    }
}
