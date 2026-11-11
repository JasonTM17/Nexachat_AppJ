package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.RefreshRequest;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.SessionSnapshot;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import okhttp3.ResponseBody;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/** A terminal response from an old session cannot close or misclassify a newer session. */
public final class TokenRefresherStaleTerminalResponseTest {

    @Test
    public void lateDefinitiveRejectionCannotCloseNewSessionAdmission() throws Exception {
        Harness harness = new Harness(401, "TOKEN_INVALID");
        TestObserver<TokenRefreshOutcome> result = harness.refresher.refresh().test();

        harness.awaitErrorBodyRead();
        harness.establishSessionB();
        harness.releaseErrorBody();

        result.awaitDone(5, TimeUnit.SECONDS).assertNoErrors();
        assertCurrentSessionB(harness);
        verify(harness.store, never()).clear();
        harness.events.getEvents().test().assertNoValues();
    }

    @Test
    public void staleRecoveryUnavailableResponseDoesNotBecomeSessionExpiredForNewSession()
            throws Exception {
        Harness harness = new Harness(409, "REFRESH_RECOVERY_UNAVAILABLE");
        TestObserver<TokenRefreshOutcome> result = harness.refresher.refresh().test();

        harness.awaitErrorBodyRead();
        harness.establishSessionB();
        harness.releaseErrorBody();

        result.awaitDone(5, TimeUnit.SECONDS).assertNoErrors();
        assertCurrentSessionB(harness);
        assertTrue(result.values().get(0) instanceof TokenRefreshOutcome.NoRefreshToken);
        verify(harness.store, never()).markSessionReauthRequired(anyLong());
        harness.events.getEvents().test().assertNoValues();
    }

    @Test
    public void recoveryCheckAndCloseAreAtomicWithAccountSwitch() throws Exception {
        Harness harness = new Harness(409, "REFRESH_RECOVERY_UNAVAILABLE");
        harness.releaseErrorBody();
        harness.refresher.terminalRecoveryTestHookAfterAdmissionCheck = harness::establishSessionB;

        TestObserver<TokenRefreshOutcome> result = harness.refresher.refresh().test();

        result.awaitDone(5, TimeUnit.SECONDS).assertNoErrors();
        assertCurrentSessionB(harness);
        assertTrue(result.values().get(0) instanceof TokenRefreshOutcome.NoRefreshToken);
        verify(harness.store, never()).markSessionReauthRequired(anyLong());
        harness.events.getEvents().test().assertNoValues();
    }

    private static void assertCurrentSessionB(Harness harness) {
        assertTrue("session B admission must remain open at epoch "
                        + harness.refresher.sessionEpoch(),
                harness.refresher.isSessionCurrent(2L));
        assertEquals("access-b", harness.tokens.get().getAccessToken());
        assertEquals("refresh-b", harness.tokens.get().getRefreshToken());
    }

    private static final class Harness {
        final AtomicReference<Tokens> tokens = new AtomicReference<>(
                new Tokens("access-a", 9_000L, "refresh-a"));
        final TokenStore store = mock(TokenStore.class);
        final SessionEvents events = new SessionEvents();
        final AuthApi api = mock(AuthApi.class);
        final CountDownLatch errorBodyEntered = new CountDownLatch(1);
        final CountDownLatch releaseErrorBody = new CountDownLatch(1);
        final TokenRefresher refresher;

        Harness(int status, String code) throws Exception {
            when(store.readTokens()).thenAnswer(ignored -> Maybe.defer(() -> Maybe.just(tokens.get())));
            when(store.readSessionSnapshot()).thenReturn(Single.just(
                    new SessionSnapshot(1L, SessionSnapshot.Lifecycle.ACTIVE)));
            when(store.bootstrapLegacySessionIfNeeded()).thenReturn(Single.just(1L));
            when(store.readPendingRefresh()).thenReturn(Maybe.empty());
            when(store.reservePendingRefresh(anyLong(), anyString(), anyString()))
                    .thenReturn(Single.just(true));
            when(store.reserveSessionClosing(anyLong())).thenReturn(Single.just(2L));
            when(store.markSessionClosed(anyLong())).thenReturn(Completable.complete());
            when(store.markSessionReauthRequired(anyLong())).thenReturn(Completable.complete());
            when(store.clear()).thenAnswer(ignored -> Completable.fromAction(() -> tokens.set(null)));
            when(store.getHasValidRefresh()).thenReturn(Flowable.just(true));

            ResponseBody errorBody = mock(ResponseBody.class);
            Call<AuthResponse> call = mock(Call.class);
            Response<AuthResponse> errorResponse = Response.error(status, errorBody);
            when(call.execute()).thenReturn(errorResponse);
            when(api.refresh(anyString(), any(RefreshRequest.class))).thenReturn(call);
            when(errorBody.string()).thenAnswer(ignored -> {
                errorBodyEntered.countDown();
                if (!releaseErrorBody.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test did not release the refresh error body");
                }
                return "{\"error\":{\"code\":\"" + code + "\"}}";
            });
            refresher = new TokenRefresher(store, events, (Provider<AuthApi>) () -> api);
        }

        void awaitErrorBodyRead() throws InterruptedException {
            assertTrue("refresh should reach response classification",
                    errorBodyEntered.await(5, TimeUnit.SECONDS));
        }

        void releaseErrorBody() {
            releaseErrorBody.countDown();
        }

        void establishSessionB() {
            tokens.set(new Tokens("access-b", 9_000L, "refresh-b"));
            refresher.onSessionEstablished(2L);
        }
    }
}
