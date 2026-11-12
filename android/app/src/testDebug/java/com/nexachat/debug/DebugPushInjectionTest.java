package com.nexachat.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import com.nexachat.call.core.CallManager;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import com.nexachat.call.core.IncomingCallPushData;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.nexachat.feature.reminder.ReminderPushEvents;
import com.nexachat.notification.FcmMessageProcessor;
import com.nexachat.notification.ForegroundConversationTracker;
import com.nexachat.notification.NotificationAdmission;
import com.nexachat.notification.NotificationWorkRequest;
import com.nexachat.notification.NotificationWorkScheduler;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class DebugPushInjectionTest {
    private static final long SESSION_EPOCH = 7L;
    private static final String RECIPIENT_ID = "user-me";
    private static final String OCCURRENCE = "0123456789abcdef0123456789abcdef";

    private CallManager callManager;
    private FcmMessageProcessor.IncomingCallLauncher launcher;
    private NotificationAdmission notificationAdmission;
    private NotificationWorkScheduler notificationWorkScheduler;
    private AtomicLong clock;
    private FcmMessageProcessor processor;

    @Before
    public void setUp() {
        callManager = mock(CallManager.class);
        Context context = mock(Context.class);
        launcher = mock(FcmMessageProcessor.IncomingCallLauncher.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        TokenStore tokenStore = mock(TokenStore.class);
        notificationAdmission = mock(NotificationAdmission.class);
        notificationWorkScheduler = mock(NotificationWorkScheduler.class);
        ForegroundConversationTracker foreground = mock(ForegroundConversationTracker.class);
        clock = new AtomicLong(1_800_000_000_000L);
        AtomicReference<IncomingCallPushData> lastAdmittedPush = new AtomicReference<>();
        when(context.getApplicationContext()).thenReturn(context);
        when(tokenRefresher.sessionSignals()).thenReturn(Flowable.empty());
        when(tokenRefresher.sessionEpoch()).thenReturn(SESSION_EPOCH);
        when(tokenRefresher.isSessionCurrent(SESSION_EPOCH)).thenReturn(true);
        when(tokenRefresher.runIfSessionCurrent(eq(SESSION_EPOCH), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return true;
                });
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt(RECIPIENT_ID), clock.get() + 60_000L, "refresh")));
        when(notificationAdmission.admitPush(anyString(), anyString())).thenAnswer(invocation ->
                Maybe.just(new NotificationAdmission.NotificationSession(
                        invocation.getArgument(0), SESSION_EPOCH)));
        when(callManager.admitIncomingPush(
                        anyString(), anyString(), anyString(), anyString(), anyLong(),
                        anyString(), anyLong(), anyLong(), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    lastAdmittedPush.set(new IncomingCallPushData(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            invocation.getArgument(4),
                            invocation.getArgument(5),
                            invocation.getArgument(7),
                            invocation.getArgument(6)));
                    return Maybe.just(new CallSession(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            CallSession.Direction.INCOMING,
                            CallState.PENDING_INCOMING,
                            null,
                            invocation.getArgument(4),
                            null,
                            false,
                            false));
                });
        when(callManager.currentIncomingPushData(anyString())).thenAnswer(invocation -> {
            IncomingCallPushData data = lastAdmittedPush.get();
            return data != null && invocation.getArgument(0).equals(data.getCallId()) ? data : null;
        });
        processor = new FcmMessageProcessor(
                callManager,
                context,
                launcher,
                new ReminderPushEvents(),
                tokenRefresher,
                tokenStore,
                new Moshi.Builder().build(),
                notificationAdmission,
                notificationWorkScheduler,
                foreground,
                Schedulers.trampoline(),
                clock::get);
    }

    @Test
    public void shellAndRootMayInjectOnlyWhenSenderUidIsVisible() {
        assertEquals(2000, DebugPushInjectionReceiver.SHELL_UID);
        assertEquals(0, DebugPushInjectionReceiver.ROOT_UID);
        assertEquals(-1, DebugPushInjectionReceiver.INVALID_UID);
        assertTrue(DebugPushInjectionReceiver.isAllowedSender(DebugPushInjectionReceiver.SHELL_UID, 36));
        assertTrue(DebugPushInjectionReceiver.isAllowedSender(DebugPushInjectionReceiver.ROOT_UID, 34));
        assertFalse(DebugPushInjectionReceiver.isAllowedSender(1000, 36));
        assertFalse(DebugPushInjectionReceiver.isAllowedSender(10_123, 36));
        assertFalse(DebugPushInjectionReceiver.isAllowedSender(DebugPushInjectionReceiver.INVALID_UID, 36));
        assertFalse(DebugPushInjectionReceiver.isAllowedSender(DebugPushInjectionReceiver.SHELL_UID, 33));
        assertTrue(DebugPushInjectionReceiver.COLD_START_HOLD_MS < 10_000L);
    }

    @Test
    public void injectedMessagePayloadYieldsTheSameWorkRequestAsFcm() {
        Map<String, String> fcm = messagePayload();
        Map<String, Object> extras = new LinkedHashMap<>(fcm);
        extras.put("android.intent.extra.REFERRER", "ignored");
        extras.put("seqAsNumber", 8);

        Map<String, String> injected = DebugPushInjectionReceiver.payloadFromExtras(extras);
        assertEquals(fcm, injected);
        assertFalse(injected.containsKey("android.intent.extra.REFERRER"));

        NotificationWorkRequest fromInjection = enqueueOnce(injected);
        NotificationWorkRequest fromFcm = enqueueOnce(fcm);
        assertEquals(fromFcm.toData().getKeyValueMap(), fromInjection.toData().getKeyValueMap());
        assertEquals(
                "msg:" + RECIPIENT_ID + ":" + SESSION_EPOCH + ":message-1",
                fromInjection.uniqueWorkName());
    }

    @Test
    public void injectedReminderPayloadYieldsTheSameWorkRequestAsFcm() {
        Map<String, String> fcm = reminderPayload();
        NotificationWorkRequest fromInjection = enqueueOnce(
                DebugPushInjectionReceiver.payloadFromExtras(new LinkedHashMap<>(fcm)));
        NotificationWorkRequest fromFcm = enqueueOnce(fcm);
        assertEquals(fromFcm.toData().getKeyValueMap(), fromInjection.toData().getKeyValueMap());
        assertEquals(
                "rem:" + RECIPIENT_ID + ":" + SESSION_EPOCH + ":reminder-1:" + OCCURRENCE,
                fromInjection.uniqueWorkName());
    }

    @Test
    public void injectedPayloadWithoutRecipientFailsClosedExactlyAsFcm() {
        Map<String, String> message = messagePayload();
        message.remove("recipientId");
        processor.processDataPayload(DebugPushInjectionReceiver.payloadFromExtras(
                new LinkedHashMap<>(message)));
        processor.processDataPayload(message);

        Map<String, String> reminder = reminderPayload();
        reminder.remove("recipientId");
        processor.processDataPayload(DebugPushInjectionReceiver.payloadFromExtras(
                new LinkedHashMap<>(reminder)));
        processor.processDataPayload(reminder);

        Map<String, String> call = callPayload();
        call.remove("recipientId");
        processor.processDataPayload(DebugPushInjectionReceiver.payloadFromExtras(
                new LinkedHashMap<>(call)));
        processor.processDataPayload(call);

        verify(notificationAdmission, never()).admitPush(anyString(), anyString());
        verify(notificationWorkScheduler, never()).enqueue(any());
        verify(callManager, never()).admitIncomingPush(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyLong(), any(BooleanSupplier.class));
        verify(launcher, never()).launch(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyLong());
    }

    @Test
    public void injectedCallPayloadReachesTheSameAdmissionAsFcm() {
        Map<String, String> fcm = callPayload();
        processor.processDataPayload(DebugPushInjectionReceiver.payloadFromExtras(
                new LinkedHashMap<>(fcm)));
        processor.processDataPayload(fcm);

        long startedAt = clock.get();
        verify(callManager, org.mockito.Mockito.times(2)).admitIncomingPush(
                eq("call-100"), eq("conv-200"), eq("user-alice"), eq("Alice"),
                eq(startedAt), eq(RECIPIENT_ID), eq(SESSION_EPOCH), eq(startedAt + 45_000L),
                any(BooleanSupplier.class));
        verify(launcher, org.mockito.Mockito.times(2)).launch(
                "call-100", "conv-200", "user-alice", "Alice", startedAt,
                RECIPIENT_ID, startedAt + 45_000L, SESSION_EPOCH);
    }

    private NotificationWorkRequest enqueueOnce(Map<String, String> payload) {
        clearInvocations(notificationWorkScheduler);
        processor.processDataPayload(payload);
        ArgumentCaptor<NotificationWorkRequest> captor =
                ArgumentCaptor.forClass(NotificationWorkRequest.class);
        verify(notificationWorkScheduler).enqueue(captor.capture());
        return captor.getValue();
    }

    private Map<String, String> messagePayload() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", FcmMessageProcessor.TYPE_NEW_MESSAGE);
        data.put("recipientId", RECIPIENT_ID);
        data.put("conversationId", "conversation-1");
        data.put("messageId", "message-1");
        data.put("seq", "8");
        return data;
    }

    private Map<String, String> reminderPayload() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", FcmMessageProcessor.TYPE_REMINDER);
        data.put("recipientId", RECIPIENT_ID);
        data.put("conversationId", "conversation-1");
        data.put("reminderId", "reminder-1");
        data.put("occurrence", OCCURRENCE);
        return data;
    }

    private Map<String, String> callPayload() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", FcmMessageProcessor.TYPE_CALL_INVITE);
        data.put("callId", "call-100");
        data.put("conversationId", "conv-200");
        data.put("callerId", "user-alice");
        data.put("callerName", "Alice");
        data.put("serverTs", String.valueOf(clock.get()));
        data.put("recipientId", RECIPIENT_ID);
        data.put("ringExpiresAt", String.valueOf(clock.get() + 45_000L));
        return data;
    }

    private static String jwt(String userId) {
        String payload = "{\"sub\":\"" + userId + "\"}";
        return "e30." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }
}
