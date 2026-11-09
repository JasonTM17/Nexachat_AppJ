package com.nexachat.notification;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.lifecycle.MutableLiveData;
import com.nexachat.call.core.CallManager;
import com.nexachat.data.SyncEngine;
import com.nexachat.feature.reminder.ReminderPushEvents;
import io.reactivex.rxjava3.core.Completable;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public final class FcmMessageProcessorTest {

    private CallManager callManager;
    private SyncEngine syncEngine;
    private Context context;
    private FcmMessageProcessor.IncomingCallLauncher launcher;
    private ReminderPushEvents reminderPushEvents;
    private FcmMessageProcessor processor;

    @Before
    public void setUp() {
        callManager = mock(CallManager.class);
        syncEngine = mock(SyncEngine.class);
        context = mock(Context.class);
        launcher = mock(FcmMessageProcessor.IncomingCallLauncher.class);
        reminderPushEvents = new ReminderPushEvents();
        when(context.getApplicationContext()).thenReturn(context);
        when(callManager.session()).thenReturn(new MutableLiveData<>());

        processor = new FcmMessageProcessor(
                callManager, syncEngine, context, launcher, reminderPushEvents);
    }

    @Test
    public void processDataPayloadNullOrEmptyDoesNothing() {
        processor.processDataPayload(null);
        processor.processDataPayload(Map.of());

        verify(callManager, never()).hydrateIncomingCall(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(launcher, never()).launch(anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(syncEngine, never()).sync();
    }

    @Test
    public void processCallInviteHydratesCallSessionAndStartsActivity() {
        Map<String, String> data = new HashMap<>();
        data.put("type", FcmMessageProcessor.TYPE_CALL_INVITE);
        data.put("callId", "call-100");
        data.put("conversationId", "conv-200");
        data.put("callerId", "user-alice");
        data.put("callerName", "Alice");
        data.put("serverTs", "1700000000000");

        processor.processDataPayload(data);

        verify(callManager).hydrateIncomingCall("call-100", "conv-200", "user-alice", "Alice", 1700000000000L);
        verify(launcher).launch("call-100", "conv-200", "user-alice", "Alice", 1700000000000L);
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
    public void ignoresUnknownPayloadTypes() {
        processor.processDataPayload(Map.of("type", "UNRECOGNIZED"));

        verify(callManager, never()).hydrateIncomingCall(
                anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(launcher, never()).launch(
                anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(syncEngine, never()).sync();
    }

    @Test
    public void ignoresMalformedCallInviteAndNewMessagePayloads() {
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_CALL_INVITE,
                "callId", " ",
                "conversationId", "conversation-1",
                "callerId", "caller-1"));
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_CALL_INVITE,
                "callId", "call-1",
                "conversationId", "conversation-1",
                "callerId", "caller-1",
                "timestamp", "not-a-timestamp"));
        processor.processDataPayload(Map.of("type", FcmMessageProcessor.TYPE_NEW_MESSAGE));
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_NEW_MESSAGE,
                "conversationId", "conversation-1",
                "messageId", "message-1",
                "seq", "zero"));

        verify(callManager, never()).hydrateIncomingCall(
                anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(launcher, never()).launch(
                anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(syncEngine, never()).sync();
    }

    @Test
    public void acceptsServerCallTimestampField() {
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_CALL_INVITE,
                "callId", "call-100",
                "conversationId", "conv-200",
                "callerId", "user-alice",
                "callerName", "Alice",
                "timestamp", "1700000000000"));

        verify(callManager).hydrateIncomingCall(
                "call-100", "conv-200", "user-alice", "Alice", 1700000000000L);
        verify(launcher).launch(
                "call-100", "conv-200", "user-alice", "Alice", 1700000000000L);
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

        org.junit.Assert.assertEquals(
                java.util.List.of("conversation-1"), refreshed);
        processor.processDataPayload(Map.of(
                "type", FcmMessageProcessor.TYPE_REMINDER,
                "conversationId", "conversation-1"));
        org.junit.Assert.assertEquals(
                java.util.List.of("conversation-1"), refreshed);
        subscription.dispose();
        verify(callManager, never()).hydrateIncomingCall(
                anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(launcher, never()).launch(
                anyString(), anyString(), anyString(), anyString(), anyLong());
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
        Map<String, String> data = Map.of(
                "type", FcmMessageProcessor.TYPE_CALL_INVITE,
                "callId", "call-id",
                "conversationId", "conversation-id",
                "callerId", "caller-id",
                "callerName", "x".repeat(600));

        processor.processDataPayload(data);

        verify(callManager, never()).hydrateIncomingCall(
                anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(launcher, never()).launch(
                anyString(), anyString(), anyString(), anyString(), anyLong());
        verify(syncEngine, never()).sync();
    }
}
