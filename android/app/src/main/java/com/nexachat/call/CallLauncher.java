package com.nexachat.call;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import com.nexachat.call.ui.CallActivity;

/** Public entry point for opening an outgoing one-to-one call screen. */
public final class CallLauncher {
    public static final String EXTRA_CONVERSATION_ID = "com.nexachat.call.CONVERSATION_ID";
    public static final String EXTRA_PEER_USER_ID = "com.nexachat.call.PEER_USER_ID";
    public static final String EXTRA_PEER_NAME = "com.nexachat.call.PEER_NAME";
    public static final String EXTRA_PEER_PHONE = "com.nexachat.call.PEER_PHONE";

    private CallLauncher() { }

    public static void start(
            Context context,
            String conversationId,
            String peerUserId,
            String peerDisplayName) {
        start(context, conversationId, peerUserId, peerDisplayName, null);
    }

    /** Optional phone number enables the ACTION_DIAL fallback if media setup fails. */
    public static void start(
            Context context,
            String conversationId,
            String peerUserId,
            String peerDisplayName,
            String peerPhoneNumber) {
        if (context == null || blank(conversationId) || blank(peerUserId)) return;
        Intent intent = new Intent(context, CallActivity.class)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(EXTRA_PEER_USER_ID, peerUserId)
                .putExtra(EXTRA_PEER_NAME, peerDisplayName)
                .putExtra(EXTRA_PEER_PHONE, peerPhoneNumber);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
