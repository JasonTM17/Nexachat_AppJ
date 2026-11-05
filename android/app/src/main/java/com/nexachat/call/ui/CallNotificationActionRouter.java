package com.nexachat.call.ui;

/** Resolves notification actions without depending on Android Intent in tests. */
public final class CallNotificationActionRouter {
    public enum Action { OPEN, ACCEPT, DECLINE, HANG_UP, UNKNOWN }

    public static final String ACTION_OPEN = "com.nexachat.call.OPEN";
    public static final String ACTION_ACCEPT = "com.nexachat.call.ACCEPT";
    public static final String ACTION_DECLINE = "com.nexachat.call.DECLINE";
    public static final String ACTION_HANG_UP = "com.nexachat.call.HANG_UP";

    private CallNotificationActionRouter() { }

    public static Action resolve(String action) {
        if (ACTION_OPEN.equals(action)) return Action.OPEN;
        if (ACTION_ACCEPT.equals(action)) return Action.ACCEPT;
        if (ACTION_DECLINE.equals(action)) return Action.DECLINE;
        if (ACTION_HANG_UP.equals(action)) return Action.HANG_UP;
        return Action.UNKNOWN;
    }
}
