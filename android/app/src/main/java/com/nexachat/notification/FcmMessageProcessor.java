package com.nexachat.notification;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.nexachat.call.core.CallManager;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.IncomingCallPushData;
import com.nexachat.call.service.CallNotifications;
import com.nexachat.call.ui.CallNotificationActionRouter;
import com.nexachat.call.ui.IncomingCallIntentCodec;
import com.nexachat.call.ui.IncomingCallActivity;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.nexachat.data.TimeAndJwt;
import com.nexachat.feature.reminder.ReminderPushEvents;
import com.squareup.moshi.Moshi;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.function.LongSupplier;
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
    /**
     * A reminder title alone may be 200 Vietnamese characters (up to 600 UTF-8
     * bytes), so the pointer-sized 512-byte guard would drop valid reminders.
     * The FCM data limit is 4 KiB; 2 KiB leaves headroom without accepting
     * arbitrary content.
     */
    private static final int MAX_REMINDER_PAYLOAD_BYTES = 2048;
    public static final String TYPE_CALL_INVITE = "CALL_INVITE";
    public static final String TYPE_NEW_MESSAGE = "NEW_MESSAGE";
    public static final String TYPE_REMINDER = "REMINDER";

    @FunctionalInterface
    public interface IncomingCallLauncher {
        void launch(
                String callId,
                String conversationId,
                String callerId,
                String callerName,
                long serverTs,
                String recipientId,
                long ringExpiresAtMs,
                long sessionEpoch);
    }

    private final CallManager callManager;
    private final Context context;
    private final IncomingCallLauncher callLauncher;
    private final ReminderPushEvents reminderPushEvents;
    private final TokenRefresher tokenRefresher;
    private final TokenStore tokenStore;
    private final Moshi moshi;
    private final NotificationAdmission notificationAdmission;
    private final NotificationWorkScheduler notificationWorkScheduler;
    private final ForegroundConversationTracker foregroundConversationTracker;
    private final Scheduler ioScheduler;
    private final LongSupplier clock;
    private final CompositeDisposable subscriptions = new CompositeDisposable();

    @Inject
    public FcmMessageProcessor(
            @NonNull CallManager callManager,
            @NonNull @ApplicationContext Context context,
            @NonNull ReminderPushEvents reminderPushEvents,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull TokenStore tokenStore,
            @NonNull Moshi moshi,
            @NonNull NotificationAdmission notificationAdmission,
            @NonNull NotificationWorkScheduler notificationWorkScheduler,
            @NonNull ForegroundConversationTracker foregroundConversationTracker) {
        this(callManager, context, defaultLauncher(context), reminderPushEvents,
                tokenRefresher, tokenStore, moshi, notificationAdmission,
                notificationWorkScheduler, foregroundConversationTracker,
                Schedulers.io(), System::currentTimeMillis);
    }

    @VisibleForTesting
    public FcmMessageProcessor(
            @NonNull CallManager callManager,
            @NonNull Context context,
            @NonNull IncomingCallLauncher callLauncher,
            @NonNull ReminderPushEvents reminderPushEvents,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull TokenStore tokenStore,
            @NonNull Moshi moshi,
            @NonNull NotificationAdmission notificationAdmission,
            @NonNull NotificationWorkScheduler notificationWorkScheduler,
            @NonNull ForegroundConversationTracker foregroundConversationTracker,
            @NonNull Scheduler ioScheduler,
            @NonNull LongSupplier clock) {
        this.callManager = callManager;
        this.context = context.getApplicationContext();
        this.callLauncher = callLauncher;
        this.reminderPushEvents = reminderPushEvents;
        this.tokenRefresher = tokenRefresher;
        this.tokenStore = tokenStore;
        this.moshi = moshi;
        this.notificationAdmission = notificationAdmission;
        this.notificationWorkScheduler = notificationWorkScheduler;
        this.foregroundConversationTracker = foregroundConversationTracker;
        this.ioScheduler = ioScheduler;
        this.clock = clock;
        subscriptions.add(this.tokenRefresher.sessionSignals().subscribe(signal -> {
            if (signal instanceof TokenRefresher.SessionSignal.EpochChanged
                    || signal instanceof TokenRefresher.SessionSignal.AdmissionChanged) {
                if (signal instanceof TokenRefresher.SessionSignal.AdmissionChanged changed
                        && !changed.admitted()) {
                    CallNotifications.clearIncoming(this.context);
                }
                this.callManager.onSessionStateChanged();
            }
        }, ignored -> { }));
    }

    private static IncomingCallLauncher defaultLauncher(Context appContext) {
        return (callId, conversationId, callerId, callerName, serverTs,
                recipientId, ringExpiresAtMs, sessionEpoch) -> {
            try {
                IncomingCallPushData pushData = new IncomingCallPushData(
                        callId,
                        conversationId,
                        callerId,
                        callerName,
                        serverTs,
                        recipientId,
                        ringExpiresAtMs,
                        sessionEpoch);
                Intent intent = new Intent(appContext, IncomingCallActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                intent.setAction(CallNotificationActionRouter.ACTION_OPEN);
                IncomingCallIntentCodec.putPushData(intent, pushData);
                appContext.startActivity(intent);
            } catch (Throwable ignored) {
            }
        };
    }

    public void processDataPayload(@Nullable Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        String type = data.get("type");
        int maxPayloadBytes = TYPE_REMINDER.equalsIgnoreCase(type)
                ? MAX_REMINDER_PAYLOAD_BYTES
                : MAX_PAYLOAD_BYTES;
        if (exceedsPayloadLimit(data, maxPayloadBytes)) {
            return;
        }
        if (TYPE_CALL_INVITE.equalsIgnoreCase(type)) {
            handleCallInvite(data);
        } else if (TYPE_NEW_MESSAGE.equalsIgnoreCase(type)) {
            handleNewMessage(data);
        } else if (TYPE_REMINDER.equalsIgnoreCase(type)) {
            handleReminder(data);
        }
    }

    private static boolean exceedsPayloadLimit(
            Map<String, String> data, int maxPayloadBytes) {
        int byteCount = 0;
        for (Entry<String, String> entry : data.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                return true;
            }
            byteCount = addUtf8Bytes(entry.getKey(), byteCount, maxPayloadBytes);
            if (byteCount > maxPayloadBytes) {
                return true;
            }
            byteCount = addUtf8Bytes(entry.getValue(), byteCount, maxPayloadBytes);
            if (byteCount > maxPayloadBytes) {
                return true;
            }
        }
        return false;
    }

    private static int addUtf8Bytes(String value, int currentCount, int maxPayloadBytes) {
        int count = currentCount;
        for (int i = 0; i < value.length() && count <= maxPayloadBytes; i++) {
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

        String recipientId = data.get("recipientId");
        long ringExpiresAtMs;
        try {
            ringExpiresAtMs = Long.parseLong(data.get("ringExpiresAt"));
        } catch (RuntimeException malformedExpiry) {
            return;
        }
        long now = clock.getAsLong();
        if (!hasText(callId)
                || !hasText(conversationId)
                || !hasText(callerId)
                || !hasText(recipientId)
                || serverTs <= 0L
                || ringExpiresAtMs <= serverTs
                || ringExpiresAtMs <= now) {
            return;
        }

        final long callStartedAtMs = serverTs;
        final String callCallerId = callerId;
        final String callCallerName = callerName;
        Disposable credentialWait = currentSessionCredential()
                .subscribeOn(ioScheduler).subscribe(credential -> {
            String currentUserId = TimeAndJwt.userIdFromJwt(credential.accessToken, moshi);
            if (!recipientId.equals(currentUserId)
                    || !tokenRefresher.isSessionCurrent(credential.sessionEpoch)) {
                return;
            }
            Disposable admission = callManager.admitIncomingPush(
                            callId,
                            conversationId,
                            callCallerId,
                            callCallerName,
                            callStartedAtMs,
                            recipientId,
                            credential.sessionEpoch,
                            ringExpiresAtMs,
                            () -> tokenRefresher.isSessionCurrent(credential.sessionEpoch))
                    .subscribeOn(ioScheduler)
                    .subscribe(session -> tokenRefresher.runIfSessionCurrent(
                            credential.sessionEpoch,
                            () -> {
                                IncomingCallPushData pushData =
                                        callManager.currentIncomingPushData(callId);
                                if (pushData == null || !pushData.isValidAt(clock.getAsLong())) return;
                                callLauncher.launch(
                                        pushData.getCallId(),
                                        pushData.getConversationId(),
                                        pushData.getCallerId(),
                                        pushData.getCallerName(),
                                        pushData.getServerTs(),
                                        pushData.getRecipientId(),
                                        pushData.getRingExpiresAtMs(),
                                        pushData.getSessionEpoch());
                                try {
                                    CallNotifications.showIncoming(context, session, pushData);
                                } catch (Throwable ignored) {
                                }
                            }), ignored -> { });
        }, ignored -> { });
    }

    private Maybe<SessionCredential> currentSessionCredential() {
        long observedEpoch = tokenRefresher.sessionEpoch();
        if (tokenRefresher.isSessionCurrent(observedEpoch)) {
            return tokenStore.readTokens()
                    .filter(ignored -> tokenRefresher.isSessionCurrent(observedEpoch))
                    .map(tokens -> new SessionCredential(tokens, observedEpoch));
        }
        return tokenRefresher.refreshIfNeeded(null).flatMapMaybe(accessToken -> {
            if (accessToken.isEmpty()) return Maybe.empty();
            long currentEpoch = tokenRefresher.sessionEpoch();
            return tokenRefresher.isSessionCurrent(currentEpoch)
                    ? Maybe.just(new SessionCredential(accessToken.get(), currentEpoch))
                    : Maybe.empty();
        });
    }

    private static final class SessionCredential {
        final String accessToken;
        final long sessionEpoch;

        SessionCredential(Tokens tokens, long sessionEpoch) {
            this(tokens.getAccessToken(), sessionEpoch);
        }

        SessionCredential(String accessToken, long sessionEpoch) {
            this.accessToken = accessToken;
            this.sessionEpoch = sessionEpoch;
        }
    }

    private void handleReminder(@NonNull Map<String, String> data) {
        String reminderId = data.get("reminderId");
        String conversationId = data.get("conversationId");
        String recipientId = data.get("recipientId");
        String occurrence = data.get("occurrence");
        if (!hasText(reminderId) || !hasText(conversationId)
                || !hasText(recipientId) || !hasText(occurrence)) {
            return;
        }
        if (!NotificationWorkRequest.isOccurrenceKey(occurrence)) {
            return;
        }
        Disposable admission = notificationAdmission.admitPush(recipientId, conversationId)
                .subscribeOn(ioScheduler)
                .subscribe(session -> {
                    if (!recipientId.equals(session.recipientId())) return;
                    tokenRefresher.runIfSessionCurrent(session.sessionEpoch(), () -> {
                        reminderPushEvents.onReminderPush(conversationId);
                        if (foregroundConversationTracker.isForegroundConversation(conversationId)) return;
                        notificationWorkScheduler.enqueue(NotificationWorkRequest.reminder(
                                recipientId, session.sessionEpoch(), conversationId,
                                reminderId, occurrence));
                    });
                }, ignored -> { });
    }

    private void handleNewMessage(@NonNull Map<String, String> data) {
        String conversationId = data.get("conversationId");
        String messageId = data.get("messageId");
        String recipientId = data.get("recipientId");
        String rawSeq = data.get("seq");
        if (!hasText(conversationId) || !hasText(messageId)
                || !hasText(recipientId) || !hasText(rawSeq)) {
            return;
        }
        final long sequence;
        try {
            sequence = Long.parseLong(rawSeq);
            if (sequence <= 0L) {
                return;
            }
        } catch (NumberFormatException malformedSequence) {
            return;
        }
        Disposable admission = notificationAdmission.admitPush(recipientId, conversationId)
                .subscribeOn(ioScheduler)
                .subscribe(session -> {
                    if (!recipientId.equals(session.recipientId())
                            || foregroundConversationTracker.isForegroundConversation(conversationId)) {
                        return;
                    }
                    tokenRefresher.runIfSessionCurrent(session.sessionEpoch(), () ->
                            notificationWorkScheduler.enqueue(NotificationWorkRequest.message(
                                    recipientId, session.sessionEpoch(), conversationId,
                                    messageId, sequence)));
                }, ignored -> { });
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
