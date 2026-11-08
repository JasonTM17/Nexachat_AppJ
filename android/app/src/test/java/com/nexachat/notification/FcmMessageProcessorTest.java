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
    private FcmMessageProcessor processor;

    @Before
    public void setUp() {
        callManager = mock(CallManager.class);
        syncEngine = mock(SyncEngine.class);
        context = mock(Context.class);
        launcher = mock(FcmMessageProcessor.IncomingCallLauncher.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(callManager.session()).thenReturn(new MutableLiveData<>());

        processor = new FcmMessageProcessor(callManager, syncEngine, context, launcher);
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

        when(syncEngine.sync()).thenReturn(Completable.complete());

        processor.processDataPayload(data);

        verify(syncEngine).sync();
    }
}
