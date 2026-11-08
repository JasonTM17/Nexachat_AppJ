package com.nexachat.notification;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.nexachat.call.CallLauncher;
import com.nexachat.call.core.CallManager;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.service.CallNotifications;
import com.nexachat.call.ui.IncomingCallActivity;
import com.nexachat.data.SyncEngine;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Map.Entry;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Decoupled push notification processor handling lightweight (&lt;500B) notification pointers.
 * Pure Java logic separate from Google Play Services FirebaseMessagingService for unit testing.
 */
@Singleton
public final class FcmMessageProcessor {

    private static final int MAX_PAYLOAD_BYTES = 512;
    public static final String TYPE_CALL_INVITE = "CALL_INVITE";
    public static final String TYPE_NEW_MESSAGE = "NEW_MESSAGE";

    @FunctionalInterface
    public interface IncomingCallLauncher {
        void launch(String callId, String conversationId, String callerId, String callerName, long serverTs);
    }

    private final CallManager callManager;
    private final SyncEngine syncEngine;
    private final Context context;
    private final IncomingCallLauncher callLauncher;

    @Inject
    public FcmMessageProcessor(
            @NonNull CallManager callManager,
            @NonNull SyncEngine syncEngine,
            @NonNull @ApplicationContext Context context) {
        this(callManager, syncEngine, context, defaultLauncher(context));
    }

    @VisibleForTesting
    public FcmMessageProcessor(
            @NonNull CallManager callManager,
            @NonNull SyncEngine syncEngine,
            @NonNull Context context,
            @NonNull IncomingCallLauncher callLauncher) {
        this.callManager = callManager;
        this.syncEngine = syncEngine;
        this.context = context.getApplicationContext();
        this.callLauncher = callLauncher;
    }

    private static IncomingCallLauncher defaultLauncher(Context appContext) {
        return (callId, conversationId, callerId, callerName, serverTs) -> {
            try {
                Intent intent = new Intent(appContext, IncomingCallActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                intent.putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId);
                intent.putExtra(CallLauncher.EXTRA_CONVERSATION_ID, conversationId);
                intent.putExtra(CallLauncher.EXTRA_PEER_USER_ID, callerId);
                intent.putExtra(CallLauncher.EXTRA_PEER_NAME, callerName);
                intent.putExtra("EXTRA_SERVER_TS", serverTs);
                appContext.startActivity(intent);
            } catch (Throwable ignored) {
            }
        };
    }

    public void processDataPayload(@Nullable Map<String, String> data) {
        if (data == null || data.isEmpty() || exceedsPayloadLimit(data)) {
            return;
        }
        String type = data.get("type");
        if (TYPE_CALL_INVITE.equalsIgnoreCase(type)) {
            handleCallInvite(data);
        } else if (TYPE_NEW_MESSAGE.equalsIgnoreCase(type)) {
            handleNewMessage(data);
        }
    }

    private static boolean exceedsPayloadLimit(Map<String, String> data) {
        int byteCount = 0;
        for (Entry<String, String> entry : data.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                return true;
            }
            byteCount = addUtf8Bytes(entry.getKey(), byteCount);
            if (byteCount > MAX_PAYLOAD_BYTES) {
                return true;
            }
            byteCount = addUtf8Bytes(entry.getValue(), byteCount);
            if (byteCount > MAX_PAYLOAD_BYTES) {
                return true;
            }
        }
        return false;
    }

    private static int addUtf8Bytes(String value, int currentCount) {
        int count = currentCount;
        for (int i = 0; i < value.length() && count <= MAX_PAYLOAD_BYTES; i++) {
            char current = value.charAt(i);
            if (current <= 0x7F) {
                count++;
            } else if (current <= 0x7FF) {
                count += 2;
            } else if (Character.isHighSurrogate(current)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                count += 4;
                i++;
            } else {
                count += 3;
            }
        }
        return count;
    }

    private void handleCallInvite(@NonNull Map<String, String> data) {
        String callId = data.get("callId");
        String conversationId = data.get("conversationId");
        String callerId = data.get("callerId");
        if (callerId == null) {
            callerId = data.get("fromUserId");
        }
        String callerName = data.get("callerName");
        if (callerName == null) {
            callerName = data.get("fromUserName");
        }
        long serverTs = 0L;
        String rawTs = data.get("serverTs");
        if (rawTs == null) {
            rawTs = data.get("timestamp");
        }
        if (rawTs != null) {
            try {
                serverTs = Long.parseLong(rawTs);
                if (serverTs <= 0L) {
                    return;
                }
            } catch (NumberFormatException malformedTimestamp) {
                return;
            }
        }

        if (!hasText(callId) || !hasText(conversationId) || !hasText(callerId)) {
            return;
        }

        callManager.hydrateIncomingCall(callId, conversationId, callerId, callerName, serverTs);
        callLauncher.launch(callId, conversationId, callerId, callerName, serverTs);

        CallSession session = callManager.session().getValue();
        if (session != null) {
            try {
                CallNotifications.showIncoming(context, session);
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleNewMessage(@NonNull Map<String, String> data) {
        String conversationId = data.get("conversationId");
        String messageId = data.get("messageId");
        String rawSeq = data.get("seq");
        if (!hasText(conversationId) || !hasText(messageId) || !hasText(rawSeq)) {
            return;
        }
        try {
            if (Long.parseLong(rawSeq) <= 0L) {
                return;
            }
        } catch (NumberFormatException malformedSequence) {
            return;
        }
        syncEngine.sync()
                .subscribeOn(Schedulers.io())
                .subscribe(() -> { }, throwable -> { });
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
