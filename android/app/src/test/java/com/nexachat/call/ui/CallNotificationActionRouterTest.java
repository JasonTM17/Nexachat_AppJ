package com.nexachat.call.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CallNotificationActionRouterTest {
    @Test public void mapsEverySupportedActionAndRejectsUnknownValues() {
        assertEquals(CallNotificationActionRouter.Action.OPEN,
                CallNotificationActionRouter.resolve(CallNotificationActionRouter.ACTION_OPEN));
        assertEquals(CallNotificationActionRouter.Action.ACCEPT,
                CallNotificationActionRouter.resolve(CallNotificationActionRouter.ACTION_ACCEPT));
        assertEquals(CallNotificationActionRouter.Action.DECLINE,
                CallNotificationActionRouter.resolve(CallNotificationActionRouter.ACTION_DECLINE));
        assertEquals(CallNotificationActionRouter.Action.HANG_UP,
                CallNotificationActionRouter.resolve(CallNotificationActionRouter.ACTION_HANG_UP));
        assertEquals(CallNotificationActionRouter.Action.UNKNOWN,
                CallNotificationActionRouter.resolve("unknown"));
        assertEquals(CallNotificationActionRouter.Action.UNKNOWN,
                CallNotificationActionRouter.resolve(null));
    }
}
