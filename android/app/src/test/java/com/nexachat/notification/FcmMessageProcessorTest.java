package com.nexachat.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.nexachat.data.SyncEngine;
import com.nexachat.feature.reminder.ReminderPushEvents;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Before;
import org.junit.Test;

public final class FcmMessageProcessorTest {

    private static final long SESSION_EPOCH = 7L;
    private static final String RECIPIENT_ID = "user-me";

    private CallManager callManager;
    private SyncEngine syncEngine;
    private Context context;
    private FcmMessageProcessor.IncomingCallLauncher launcher;
    private ReminderPushEvents reminderPushEvents;
    private TokenRefresher tokenRefresher;
    private TokenStore tokenStore;
    private Moshi moshi;
    private AtomicLong clock;
    private AtomicReference<IncomingCallPushData> lastAdmittedPush;
    private FcmMessageProcessor processor;

    @Before
    public void setUp() {
        callManager = mock(CallManager.class);
        syncEngine = mock(SyncEngine.class);
        context = mock(Context.class);
        launcher = mock(FcmMessageProcessor.IncomingCallLauncher.class);
        reminderPushEvents = new ReminderPushEvents();
        tokenRefresher = mock(TokenRefresher.class);
        tokenStore = mock(TokenStore.class);
        moshi = new Moshi.Builder().build();
        clock = new AtomicLong(1_800_000_000_000L);
        lastAdmittedPush = new AtomicReference<>();
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
                    return Maybe.just(incomingSession(
                            invocation.getArgument(0), invocation.getArgument(1),
                            invocation.getArgument(2), invocation.getArgument(3),
                            invocation.getArgument(4)));
                });
        when(callManager.isIncomingPushAdmissionCurrent(anyString())).thenReturn(true);
        when(callManager.currentIncomingPushData(anyString())).thenAnswer(invocation -> {
            IncomingCallPushData data = lastAdmittedPush.get();
            return data != null && invocation.getArgument(0).equals(data.getCallId())
                    ? data : null;
        });

        processor = new FcmMessageProcessor(
                callManager,
                syncEngine,
                context,
                launcher,
                reminderPushEvents,
                tokenRefresher,
                tokenStore,
                moshi,
                Schedulers.trampoline(),
                clock::get);
    }

    @Test
    public void processDataPayloadNullOrEmptyDoesNothing() {
        processor.processDataPayload(null);
        processor.processDataPayload(Map.of());

        verifyNoIncomingAdmission();
        verify(syncEngine, never()).sync();
    }

    @Test
    public void launchesOnlyAfterCurrentAccountAdmissionSucceeds() {
        long startedAt = clock.get();
        long deadline = startedAt + 45_000L;
        processor.processDataPayload(callInvite("call-100", RECIPIENT_ID, startedAt, deadline));

        verify(callManager).admitIncomingPush(
                eq("call-100"), eq("conv-200"), eq("user-alice"), eq("Alice"),
                eq(startedAt), eq(RECIPIENT_ID), eq(SESSION_EPOCH), eq(deadline),
                any(BooleanSupplier.class));
        verify(launcher).launch(
                "call-100", "conv-200", "user-alice", "Alice", startedAt,
                RECIPIENT_ID, deadline, SESSION_EPOCH);
    }

    @Test
    public void coldProcessRestoresSessionBeforeCheckingPushAudience() {
        when(tokenRefresher.sessionEpoch()).thenReturn(0L, SESSION_EPOCH);
        when(tokenRefresher.isSessionCurrent(0L)).thenReturn(false);
        when(tokenRefresher.isSessionCurrent(SESSION_EPOCH)).thenReturn(true);
        when(tokenRefresher.refreshIfNeeded(null))
                .thenReturn(Single.just(java.util.Optional.of(jwt(RECIPIENT_ID))));
        long startedAt = clock.get();
        long deadline = startedAt + 45_000L;

        processor.processDataPayload(callInvite("call-cold-process", RECIPIENT_ID, startedAt, deadline));

        verify(tokenRefresher).refreshIfNeeded(null);
        verify(callManager).admitIncomingPush(
                eq("call-cold-process"), eq("conv-200"), eq("user-alice"), eq("Alice"),
                eq(startedAt), eq(RECIPIENT_ID), eq(SESSION_EPOCH), eq(deadline),
                any(BooleanSupplier.class));
        verify(launcher).launch("call-cold-process", "conv-200", "user-alice", "Alice",
                startedAt, RECIPIENT_ID, deadline, SESSION_EPOCH);
    }

    @Test
    public void managerRefusalDoesNotLaunchOrNotify() {
        long startedAt = clock.get();
        long deadline = startedAt + 45_000L;
        when(callManager.admitIncomingPush(
                        anyString(), anyString(), anyString(), anyString(), anyLong(),
                        anyString(), anyLong(), anyLong(), any(BooleanSupplier.class)))
                .thenReturn(Maybe.empty());

        processor.processDataPayload(callInvite("call-busy", RECIPIENT_ID, startedAt, deadline));

        verifyNoLaunch();
    }

    @Test
    public void rejectsQueuedCallInviteWithoutAudience() {
        Map<String, String> data = callInvite("call-queued", RECIPIENT_ID,
                clock.get(), clock.get() + 45_000L);
        data.remove("recipientId");

        processor.processDataPayload(data);

        verifyNoIncomingAdmission();
    }

    @Test
    public void rejectsLegacyTimestampOnlyPayloadWithoutAnAbsoluteExpiry() {
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_CALL_INVITE,
                "callId", "call-legacy",
                "conversationId", "conv-200",
                "callerId", "user-alice",
                "timestamp", String.valueOf(clock.get())));

        verifyNoIncomingAdmission();
    }

    @Test
    public void rejectsExpiredCallInviteBeforeAdmissionOrActivityLaunch() {
        long deadline = clock.get() - 1L;
        processor.processDataPayload(callInvite(
                "call-expired", RECIPIENT_ID, deadline - 45_000L, deadline));

        verifyNoIncomingAdmission();
    }

    @Test
    public void rejectsInviteTargetedAtAnotherAccount() {
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("user-other"), clock.get() + 60_000L, "refresh")));
        long startedAt = clock.get();

        processor.processDataPayload(callInvite(
                "call-wrong-account", RECIPIENT_ID, startedAt, startedAt + 45_000L));

        verifyNoIncomingAdmission();
    }

    @Test
    public void rejectsAdmissionCallbackAfterSessionEpochChanges() {
        when(tokenRefresher.runIfSessionCurrent(eq(SESSION_EPOCH), any(Runnable.class)))
                .thenReturn(false);
        long startedAt = clock.get();

        processor.processDataPayload(callInvite(
                "call-stale-session", RECIPIENT_ID, startedAt, startedAt + 45_000L));

        verify(callManager).admitIncomingPush(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyLong(), any(BooleanSupplier.class));
        verifyNoLaunch();
    }

    @Test
    public void processNewMessageTriggersSyncEngine() {
        Map<String, String> data = new HashMap<>();
        data.put("type", FcmMessageProcessor.TYPE_NEW_MESSAGE);
        data.put("conversationId", "conversation-1");
        data.put("messageId", "message-1");
        data.put("seq", "1");

        when(syncEngine.sync()).thenReturn(Completable.complete());
        processor.processDataPayload(data);

        verify(syncEngine).sync();
    }

    @Test
    public void ignoresUnknownAndMalformedPayloads() {
        processor.processDataPayload(Map.of("type", "UNRECOGNIZED"));
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_CALL_INVITE,
                "callId", " ",
                "conversationId", "conversation-1",
                "callerId", "caller-1",
                "recipientId", RECIPIENT_ID,
                "timestamp", "not-a-timestamp",
                "ringExpiresAt", String.valueOf(clock.get() + 45_000L)));
        processor.processDataPayload(Map.of("type", FcmMessageProcessor.TYPE_NEW_MESSAGE));
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_NEW_MESSAGE,
                "conversationId", "conversation-1",
                "messageId", "message-1",
                "seq", "zero"));

        verifyNoIncomingAdmission();
        verify(syncEngine, never()).sync();
    }

    @Test
    public void reminderPushRefreshesTheConversationBoardWithoutSyncOrCall() {
        java.util.List<String> refreshed = new java.util.ArrayList<>();
        io.reactivex.rxjava3.disposables.Disposable subscription =
                reminderPushEvents.conversationIds().subscribe(refreshed::add);
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_REMINDER,
                "reminderId", "reminder-1",
                "conversationId", "conversation-1",
                "title", "Họp team",
                "remindAt", "2026-12-01T02:00:00Z",
                "repeat", "WEEKLY"));

        org.junit.Assert.assertEquals(java.util.List.of("conversation-1"), refreshed);
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_REMINDER,
                "conversationId", "conversation-1"));
        org.junit.Assert.assertEquals(java.util.List.of("conversation-1"), refreshed);
        subscription.dispose();
        verifyNoIncomingAdmission();
        verify(syncEngine, never()).sync();
    }

    @Test
    public void acceptsALongVietnameseReminderTitleWithinItsOwnPayloadLimit() {
        java.util.List<String> refreshed = new java.util.ArrayList<>();
        io.reactivex.rxjava3.disposables.Disposable subscription =
                reminderPushEvents.conversationIds().subscribe(refreshed::add);
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_REMINDER,
                "reminderId", "reminder-1",
                "conversationId", "conversation-1",
                "title", "ắ".repeat(160),
                "remindAt", "2026-12-01T02:00:00Z",
                "repeat", "WEEKLY"));
        org.junit.Assert.assertEquals(java.util.List.of("conversation-1"), refreshed);
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_REMINDER,
                "reminderId", "reminder-2",
                "conversationId", "conversation-1",
                "title", "ắ".repeat(700)));
        org.junit.Assert.assertEquals(java.util.List.of("conversation-1"), refreshed);
        subscription.dispose();
    }

    @Test
    public void ignoresPayloadsOver512Utf8Bytes() {
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_CALL_INVITE,
                "callId", "call-id",
                "conversationId", "conversation-id",
                "callerId", "caller-id",
                "callerName", "x".repeat(600)));

        verifyNoIncomingAdmission();
        verify(syncEngine, never()).sync();
    }

    private Map<String, String> callInvite(
            String callId, String recipientId, long startedAtMs, long ringExpiresAtMs) {
        Map<String, String> data = new HashMap<>();
        data.put("type", FcmMessageProcessor.TYPE_CALL_INVITE);
        data.put("callId", callId);
        data.put("conversationId", "conv-200");
        data.put("callerId", "user-alice");
        data.put("callerName", "Alice");
        data.put("timestamp", String.valueOf(startedAtMs));
        data.put("recipientId", recipientId);
        data.put("ringExpiresAt", String.valueOf(ringExpiresAtMs));
        return data;
    }

    private static CallSession incomingSession(
            String callId,
            String conversationId,
            String callerId,
            String callerName,
            long startedAtMs) {
        return new CallSession(callId, conversationId, callerId, callerName,
                CallSession.Direction.INCOMING, CallState.PENDING_INCOMING, null,
                startedAtMs, null, false, false);
    }

    private void verifyNoIncomingAdmission() {
        verify(callManager, never()).admitIncomingPush(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyLong(), any(BooleanSupplier.class));
        verifyNoLaunch();
    }

    private void verifyNoLaunch() {
        verify(launcher, never()).launch(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyLong());
    }

    private static String jwt(String userId) {
        String payload = "{\"sub\":\"" + userId + "\"}";
        return "e30." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }
}
