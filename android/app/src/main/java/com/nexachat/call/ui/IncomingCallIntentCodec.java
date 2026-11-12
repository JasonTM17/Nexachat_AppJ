package com.nexachat.call.ui;

import android.content.Intent;
import androidx.annotation.Nullable;
import com.nexachat.call.CallLauncher;
import com.nexachat.call.core.IncomingCallPushData;

/** Encodes the immutable fields needed to restore an incoming-call notification action. */
public final class IncomingCallIntentCodec {
    private IncomingCallIntentCodec() { }

    public static void putPushData(Intent intent, IncomingCallPushData data) {
        if (intent == null || data == null) return;
        intent.putExtra(IncomingCallActivity.EXTRA_CALL_ID, data.getCallId());
        intent.putExtra(CallLauncher.EXTRA_CONVERSATION_ID, data.getConversationId());
        intent.putExtra(CallLauncher.EXTRA_PEER_USER_ID, data.getCallerId());
        intent.putExtra(CallLauncher.EXTRA_PEER_NAME, data.getCallerName());
        intent.putExtra(IncomingCallActivity.EXTRA_SERVER_TS, data.getServerTs());
        intent.putExtra(IncomingCallActivity.EXTRA_RECIPIENT_ID, data.getRecipientId());
        intent.putExtra(IncomingCallActivity.EXTRA_RING_EXPIRES_AT, data.getRingExpiresAtMs());
        intent.putExtra(IncomingCallActivity.EXTRA_SESSION_EPOCH, data.getSessionEpoch());
    }

    @Nullable
    public static IncomingCallPushData readPushData(@Nullable Intent intent) {
        if (intent == null
                || !intent.hasExtra(IncomingCallActivity.EXTRA_CALL_ID)
                || !intent.hasExtra(CallLauncher.EXTRA_CONVERSATION_ID)
                || !intent.hasExtra(CallLauncher.EXTRA_PEER_USER_ID)
                || !intent.hasExtra(IncomingCallActivity.EXTRA_SERVER_TS)
                || !intent.hasExtra(IncomingCallActivity.EXTRA_RECIPIENT_ID)
                || !intent.hasExtra(IncomingCallActivity.EXTRA_RING_EXPIRES_AT)
                || !intent.hasExtra(IncomingCallActivity.EXTRA_SESSION_EPOCH)) {
            return null;
        }
        try {
            return new IncomingCallPushData(
                    intent.getStringExtra(IncomingCallActivity.EXTRA_CALL_ID),
                    intent.getStringExtra(CallLauncher.EXTRA_CONVERSATION_ID),
                    intent.getStringExtra(CallLauncher.EXTRA_PEER_USER_ID),
                    intent.getStringExtra(CallLauncher.EXTRA_PEER_NAME),
                    intent.getLongExtra(IncomingCallActivity.EXTRA_SERVER_TS, 0L),
                    intent.getStringExtra(IncomingCallActivity.EXTRA_RECIPIENT_ID),
                    intent.getLongExtra(IncomingCallActivity.EXTRA_RING_EXPIRES_AT, 0L),
                    intent.getLongExtra(IncomingCallActivity.EXTRA_SESSION_EPOCH, -1L));
        } catch (RuntimeException malformedIntent) {
            return null;
        }
    }
}
