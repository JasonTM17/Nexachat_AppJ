package com.nexachat.call;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import com.nexachat.call.core.CallMediaType;
import com.nexachat.call.ui.CallActivity;

/** Public entry point for opening an outgoing one-to-one call screen. */
public final class CallLauncher {
    public static final String EXTRA_CONVERSATION_ID = "com.nexachat.call.CONVERSATION_ID";
    public static final String EXTRA_PEER_USER_ID = "com.nexachat.call.PEER_USER_ID";
    public static final String EXTRA_PEER_NAME = "com.nexachat.call.PEER_NAME";
    public static final String EXTRA_PEER_PHONE = "com.nexachat.call.PEER_PHONE";
    public static final String EXTRA_MEDIA_TYPE = "com.nexachat.call.MEDIA_TYPE";

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
        start(context, conversationId, peerUserId, peerDisplayName, peerPhoneNumber,
                CallMediaType.AUDIO);
    }

    /** Starts the call screen requesting the given media kind for a new outgoing call. */
    public static void start(
            Context context,
            String conversationId,
            String peerUserId,
            String peerDisplayName,
            String peerPhoneNumber,
            CallMediaType mediaType) {
        if (context == null || blank(conversationId) || blank(peerUserId)) return;
        Intent intent = new Intent(context, CallActivity.class)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(EXTRA_PEER_USER_ID, peerUserId)
                .putExtra(EXTRA_PEER_NAME, peerDisplayName)
                .putExtra(EXTRA_PEER_PHONE, peerPhoneNumber)
                .putExtra(EXTRA_MEDIA_TYPE, CallMediaType.orAudio(mediaType).name());
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
