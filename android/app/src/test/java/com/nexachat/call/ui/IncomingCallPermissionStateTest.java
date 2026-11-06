package com.nexachat.call.ui;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class IncomingCallPermissionStateTest {
    @Test
    public void rotationDuringRationaleAllowsAcceptToBeRetried() {
        boolean restoredPendingAccept =
                IncomingCallPermissionState.pendingAcceptAfterRecreation(true);

        assertFalse(restoredPendingAccept);
        assertTrue(IncomingCallPermissionState.canStartAccept(restoredPendingAccept, true));
    }

    @Test
    public void anExistingRequestStillPreventsDuplicateAcceptTaps() {
        assertFalse(IncomingCallPermissionState.canStartAccept(true, true));
    }
}
