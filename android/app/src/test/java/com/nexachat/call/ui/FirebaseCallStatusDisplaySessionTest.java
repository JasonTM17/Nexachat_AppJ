package com.nexachat.call.ui;

import com.nexachat.R;
import com.nexachat.call.core.CallEndReason;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import com.nexachat.call.ui.FirebaseCallStatusDisplayAdapter.CallStatusDisplay;
import com.nexachat.call.ui.FirebaseCallStatusDisplayAdapter.State;
import com.nexachat.core.realtime.ephemeral.FirebaseCallStatusListener;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class FirebaseCallStatusDisplaySessionTest {
    private static final String CALL_ID = "33333333-3333-4333-8333-333333333333";
    private static final String CONVERSATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String CALLER_ID = "11111111-1111-4111-8111-111111111111";
    private static final String CALLEE_ID = "22222222-2222-4222-8222-222222222222";

    @Test
    public void enabledSessionWaitsForBridgeAndDisplaysAuthenticatedProjection() {
        FakeRegistrar registrar = new FakeRegistrar();
        AtomicReference<CallStatusDisplay> display = new AtomicReference<>();
        FirebaseCallStatusDisplaySession session = new FirebaseCallStatusDisplaySession(
                new FirebaseCallStatusDisplayAdapter(true), registrar, display::set);

        session.start();
        session.onCallChanged(call(CallState.CONNECTING, null));
        assertEquals(0, registrar.attachCount);
        session.onSourceReady();

        assertEquals(1, registrar.attachCount);
        registrar.listener.onStatusChanged(
                CALL_ID, CALLER_ID, projection("ACTIVE", 1_000L, 121_000L), 2_000L);

        assertEquals(State.ACTIVE, session.display().getState());
        assertEquals(CALLEE_ID, display.get().getPeerUserId());
        assertEquals(R.string.call_status_accepted,
                CallUiModelMapper.map(call(CallState.CONNECTING, null), session.display()).getTitleRes());
    }

    @Test
    public void disabledFlagDoesNotRegisterAnyFirebaseListener() {
        FakeRegistrar registrar = new FakeRegistrar();
        FirebaseCallStatusDisplaySession session = new FirebaseCallStatusDisplaySession(
                new FirebaseCallStatusDisplayAdapter(false), registrar, ignored -> { });

        session.start();
        session.onSourceReady();
        session.onCallChanged(call(CallState.CONNECTING, null));

        assertEquals(0, registrar.attachCount);
        assertNull(session.display());
        session.stop();
        assertEquals(0, registrar.disposeCount);
    }

    @Test
    public void callEndDisposesTheListenerAndClearsDisplay() {
        FakeRegistrar registrar = new FakeRegistrar();
        AtomicReference<CallStatusDisplay> display = new AtomicReference<>();
        FirebaseCallStatusDisplaySession session = new FirebaseCallStatusDisplaySession(
                new FirebaseCallStatusDisplayAdapter(true), registrar, display::set);
        session.start();
        session.onSourceReady();
        session.onCallChanged(call(CallState.CONNECTING, null));
        registrar.listener.onStatusChanged(
                CALL_ID, CALLER_ID, projection("ACTIVE", 1_000L, 121_000L), 2_000L);
        CallStatusDisplay activeProjection = session.display();

        session.onCallChanged(call(CallState.ENDED, CallEndReason.BUSY));

        assertEquals(1, registrar.disposeCount);
        assertNull(session.display());
        assertNull(display.get());
        assertEquals(R.string.call_state_busy,
                CallUiModelMapper.map(call(CallState.ENDED, CallEndReason.BUSY), activeProjection)
                        .getTitleRes());
        assertFalse(CallUiModelMapper.map(call(CallState.ENDED, CallEndReason.BUSY), activeProjection)
                .isShowHangUp());
    }

    @Test
    public void activityStopDisposesTheListener() {
        FakeRegistrar registrar = new FakeRegistrar();
        FirebaseCallStatusDisplaySession session = new FirebaseCallStatusDisplaySession(
                new FirebaseCallStatusDisplayAdapter(true), registrar, ignored -> { });
        session.start();
        session.onSourceReady();
        session.onCallChanged(call(CallState.CONNECTING, null));

        session.stop();
        assertEquals(1, registrar.disposeCount);
        assertNull(session.display());
    }

    @Test
    public void authenticatedBridgeSessionCloseDisposesTheListener() {
        FakeRegistrar registrar = new FakeRegistrar();
        FirebaseCallStatusDisplaySession session = new FirebaseCallStatusDisplaySession(
                new FirebaseCallStatusDisplayAdapter(true), registrar, ignored -> { });
        session.start();
        session.onSourceReady();
        session.onCallChanged(call(CallState.OUTGOING_RINGING, null));

        registrar.listener.onUnavailable();

        assertEquals(1, registrar.disposeCount);
        assertNull(session.display());
        session.onSourceReady();
        assertEquals(2, registrar.attachCount);
    }

    private static CallSession call(CallState state, CallEndReason reason) {
        return new CallSession(CALL_ID, CONVERSATION_ID, CALLEE_ID, "Peer",
                CallSession.Direction.OUTGOING, state, reason, 1_000L, 2_000L, false, false);
    }

    private static Map<String, Object> projection(String state, long updatedAt, long expiresAt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("cid", CONVERSATION_ID);
        value.put("callerId", CALLER_ID);
        value.put("calleeId", CALLEE_ID);
        value.put("state", state);
        value.put("updatedAt", updatedAt);
        value.put("expiresAt", expiresAt);
        return value;
    }

    private static final class FakeRegistrar
            implements FirebaseCallStatusDisplaySession.ListenerRegistrar {
        int attachCount;
        int disposeCount;
        FirebaseCallStatusListener listener;

        @Override
        public Disposable listen(String callId, FirebaseCallStatusListener nextListener) {
            assertEquals(CALL_ID, callId);
            attachCount++;
            listener = nextListener;
            return new Disposable() {
                private boolean disposed;

                @Override
                public void dispose() {
                    if (!disposed) {
                        disposed = true;
                        disposeCount++;
                    }
                }

                @Override
                public boolean isDisposed() {
                    return disposed;
                }
            };
        }
    }
}
