package com.nexachat.call.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.Before;
import org.junit.Test;

public final class IncomingCallPushAdmissionTest {
    private static final long SESSION_EPOCH = 7L;
    private static final long NOW = 1_800_000_000_000L;
    private static final long RING_EXPIRES_AT = NOW + 45_000L;

    private CallManager callManager;
    private TokenRefresher tokenRefresher;
    private TokenStore tokenStore;
    private AtomicLong clock;
    private IncomingCallPushAdmission admission;
    private IncomingCallPushData data;
    private CallSession session;

    @Before
    public void setUp() {
        callManager = mock(CallManager.class);
        tokenRefresher = mock(TokenRefresher.class);
        tokenStore = mock(TokenStore.class);
        clock = new AtomicLong(NOW);
        admission = new IncomingCallPushAdmission(
                callManager,
                tokenRefresher,
                tokenStore,
                new Moshi.Builder().build(),
                Schedulers.trampoline(),
                clock::get);
        data = validData();
        session = new CallSession("call-100", "conv-200", "user-alice", "Alice",
                CallSession.Direction.INCOMING, CallState.PENDING_INCOMING, null,
                NOW - 1_000L, null, false, false);

        when(tokenRefresher.isSessionCurrent(SESSION_EPOCH)).thenReturn(true);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("user-me"), NOW + 60_000L, "refresh")));
        when(callManager.admitIncomingPush(
                        anyString(), anyString(), anyString(), anyString(), anyLong(),
                        anyString(), anyLong(), anyLong(), any(BooleanSupplier.class)))
                .thenReturn(Maybe.just(session));
    }

    @Test
    public void validNotificationReAdmissionChecksAudienceAndUsesEveryPushField() {
        TestObserver<CallSession> result = admission.admit(data).test();

        result.assertValue(session);
        verify(callManager).admitIncomingPush(
                eq("call-100"), eq("conv-200"), eq("user-alice"), eq("Alice"),
                eq(NOW - 1_000L), eq("user-me"), eq(SESSION_EPOCH),
                eq(RING_EXPIRES_AT), any(BooleanSupplier.class));
    }

    @Test
    public void expiredNotificationDoesNotReadCredentialsOrAdmit() {
        clock.set(RING_EXPIRES_AT);

        admission.admit(data).test().assertComplete();

        verify(tokenStore, never()).readTokens();
        verify(callManager, never()).admitIncomingPush(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyLong(), any(BooleanSupplier.class));
    }

    @Test
    public void notificationForAnotherRecipientDoesNotAdmit() {
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwt("user-other"), NOW + 60_000L, "refresh")));

        admission.admit(data).test().assertComplete();

        verify(callManager, never()).admitIncomingPush(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyLong(), any(BooleanSupplier.class));
    }

    @Test
    public void staleSessionEpochCannotReuseRestoredCredentials() {
        when(tokenRefresher.isSessionCurrent(SESSION_EPOCH)).thenReturn(false);
        when(tokenRefresher.refreshIfNeeded(null))
                .thenReturn(Single.just(Optional.of(jwt("user-me"))));

        admission.admit(data).test().assertComplete();

        verify(tokenStore, never()).readTokens();
        verify(callManager, never()).admitIncomingPush(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyLong(), any(BooleanSupplier.class));
    }

    @Test
    public void loggedOutSessionDoesNotRestoreOrAdmit() {
        when(tokenRefresher.isSessionCurrent(SESSION_EPOCH)).thenReturn(false);
        when(tokenRefresher.refreshIfNeeded(null)).thenReturn(Single.just(Optional.empty()));

        admission.admit(data).test().assertComplete();

        verify(tokenStore, never()).readTokens();
        verify(callManager, never()).admitIncomingPush(
                anyString(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyLong(), any(BooleanSupplier.class));
    }

    @Test
    public void actionRunsOnlyForTheExactCurrentCallAdmission() {
        when(callManager.currentSessionSnapshot()).thenReturn(session);
        when(callManager.isIncomingPushAdmissionCurrent(
                "call-100", "user-me", SESSION_EPOCH, RING_EXPIRES_AT)).thenReturn(true);
        when(tokenRefresher.runIfSessionCurrent(eq(SESSION_EPOCH), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return true;
                });
        AtomicBoolean actionRan = new AtomicBoolean();

        assertTrue(admission.runIfCurrent(data, () -> actionRan.set(true)));
        assertTrue(actionRan.get());

        when(callManager.isIncomingPushAdmissionCurrent(
                "call-100", "user-me", SESSION_EPOCH, RING_EXPIRES_AT)).thenReturn(false);
        actionRan.set(false);
        assertFalse(admission.runIfCurrent(data, () -> actionRan.set(true)));
        assertFalse(actionRan.get());
    }

    @Test
    public void coldProcessDeclineReAdmitsThenRejectsWithoutOpeningUi() {
        when(callManager.currentSessionSnapshot()).thenReturn(null, session);
        when(callManager.isIncomingPushAdmissionCurrent(
                "call-100", "user-me", SESSION_EPOCH, RING_EXPIRES_AT)).thenReturn(true);
        when(tokenRefresher.runIfSessionCurrent(eq(SESSION_EPOCH), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return true;
                });

        TestObserver<Boolean> result = admission.admitAndRunIfCurrent(
                data, () -> callManager.reject(data.getCallId())).test();

        result.assertValue(true);
        verify(callManager).admitIncomingPush(
                eq("call-100"), eq("conv-200"), eq("user-alice"), eq("Alice"),
                eq(NOW - 1_000L), eq("user-me"), eq(SESSION_EPOCH),
                eq(RING_EXPIRES_AT), any(BooleanSupplier.class));
        verify(callManager).reject("call-100");
    }

    private static IncomingCallPushData validData() {
        return new IncomingCallPushData(
                "call-100", "conv-200", "user-alice", "Alice", NOW - 1_000L,
                "user-me", RING_EXPIRES_AT, SESSION_EPOCH);
    }

    private static String jwt(String userId) {
        String payload = "{\"sub\":\"" + userId + "\"}";
        return "e30." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }
}
