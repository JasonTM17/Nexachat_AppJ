package com.nexachat.core.network;

import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesFactory;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.ChangePasswordRequest;
import com.nexachat.core.network.dto.LoginRequest;
import com.nexachat.core.network.dto.LogoutRequest;
import com.nexachat.core.network.dto.RefreshRequest;
import com.nexachat.core.network.dto.RegisterRequest;
import com.nexachat.core.storage.TokenCipher;
import com.nexachat.core.storage.TokenStore;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import javax.inject.Provider;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlowKt;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Test;
import retrofit2.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Session outcome of {@link TokenRefresher}: only a definitive auth rejection
 * wipes tokens. Throttle, timeout, server errors and IO keep the session.
 */
public final class TokenRefresherTest {

    @Test
    public void rateLimitPreservesTokensAndDoesNotLogOut() throws InterruptedException {
        assertSessionPreserved(429);
    }

    @Test
    public void requestTimeoutPreservesTokensAndDoesNotLogOut() throws InterruptedException {
        assertSessionPreserved(408);
    }

    @Test
    public void serverErrorPreservesTokensAndDoesNotLogOut() throws InterruptedException {
        assertSessionPreserved(503);
    }

    @Test
    public void ioExceptionPreservesTokensAndDoesNotLogOut() throws InterruptedException {
        Harness harness = Harness.seeded();
        harness.api.failure = new IOException("offline");

        TokenRefresher.Outcome outcome = harness.refresh();

        assertSame(TokenRefresher.Outcome.TransientFailure.INSTANCE, outcome);
        assertEquals("rt-live", harness.readTokens().getRefreshToken());
        assertTrue(harness.events.getEvents().getReplayCache().isEmpty());
    }

    @Test
    public void reusedRefreshTokenClearsTheSession() throws InterruptedException {
        assertDefinitiveRejection(401);
    }

    @Test
    public void validationAndForbiddenClearTheSession() throws InterruptedException {
        assertDefinitiveRejection(400);
        assertDefinitiveRejection(403);
    }

    @Test
    public void cancellationPropagatesAndKeepsTokens() throws InterruptedException {
        Harness harness = Harness.seeded();
        CancellationException cancelled = new CancellationException("cancelled");
        harness.api.failure = cancelled;

        try {
            harness.refresh();
            fail("cancellation must leave TokenRefresher");
        } catch (CancellationException actual) {
            assertSame(cancelled, actual);
        }
        assertEquals("rt-live", harness.readTokens().getRefreshToken());
        assertTrue(harness.events.getEvents().getReplayCache().isEmpty());
    }

    private static void assertSessionPreserved(int status) throws InterruptedException {
        Harness harness = Harness.seeded();
        harness.api.status = status;

        TokenRefresher.Outcome outcome = harness.refresh();

        assertSame(TokenRefresher.Outcome.TransientFailure.INSTANCE, outcome);
        assertEquals("access-live", harness.readTokens().getAccessToken());
        assertEquals("rt-live", harness.readTokens().getRefreshToken());
        assertTrue(harness.events.getEvents().getReplayCache().isEmpty());
    }

    private static void assertDefinitiveRejection(int status) throws InterruptedException {
        Harness harness = Harness.seeded();
        harness.api.status = status;

        TokenRefresher.Outcome outcome = harness.refresh();

        assertSame(TokenRefresher.Outcome.Rejected.INSTANCE, outcome);
        assertNull(harness.readTokens());
        assertEquals(
                SessionEvents.Event.LoggedOut.INSTANCE,
                harness.events.getEvents().getReplayCache().get(0)
        );
    }

    private static final class Harness {
        final MemoryPreferences preferences = new MemoryPreferences();
        final TokenStore store = new TokenStore(preferences, new PassThroughCipher());
        final SessionEvents events = new SessionEvents();
        final ScriptedAuthApi api = new ScriptedAuthApi();
        final TokenRefresher refresher = new TokenRefresher(store, events, api);

        static Harness seeded() throws InterruptedException {
            Harness harness = new Harness();
            Function2<CoroutineScope, Continuation<? super Unit>, Object> save =
                    (scope, continuation) ->
                            harness.store.saveTokens("access-live", 9_000L, "rt-live", continuation);
            BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, save);
            return harness;
        }

        TokenRefresher.Outcome refresh() throws InterruptedException {
            Function2<CoroutineScope, Continuation<? super TokenRefresher.Outcome>, Object> call =
                    (scope, continuation) -> refresher.refresh(continuation);
            return (TokenRefresher.Outcome) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, call);
        }

        TokenStore.Tokens readTokens() throws InterruptedException {
            Function2<CoroutineScope, Continuation<? super TokenStore.Tokens>, Object> read =
                    (scope, continuation) -> store.readTokens(continuation);
            return (TokenStore.Tokens) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, read);
        }
    }

    private static final class PassThroughCipher implements TokenCipher {
        @Override
        public byte[] encrypt(byte[] plaintext) {
            return plaintext.clone();
        }

        @Override
        public byte[] decrypt(byte[] packed) {
            return packed.clone();
        }
    }

    /** Enough of DataStore for {@link TokenStore}'s edit/read path. */
    private static final class MemoryPreferences implements DataStore<Preferences> {
        private final MutableStateFlow<Preferences> state =
                StateFlowKt.MutableStateFlow(PreferencesFactory.createEmpty());

        @Override
        public Flow<Preferences> getData() {
            return state;
        }

        @Override
        public Object updateData(
                Function2<? super Preferences, ? super Continuation<? super Preferences>, ?> transform,
                Continuation<? super Preferences> completion
        ) {
            Object updated = transform.invoke(state.getValue(), new Continuation<Preferences>() {
                @Override
                public CoroutineContext getContext() {
                    return EmptyCoroutineContext.INSTANCE;
                }

                @Override
                public void resumeWith(Object result) {
                    throw new IllegalStateException("token store transform suspended");
                }
            });
            if (!(updated instanceof Preferences)) {
                throw new IllegalStateException("token store transform did not return preferences");
            }
            Preferences next = (Preferences) updated;
            state.setValue(next);
            return next;
        }
    }

    private static final class ScriptedAuthApi implements AuthApi, Provider<AuthApi> {
        int status = 500;
        Throwable failure;

        @Override
        public AuthApi get() {
            return this;
        }

        @Override
        public Object refresh(RefreshRequest body, Continuation<? super Response<AuthResponse>> continuation) {
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            if (failure instanceof IOException) {
                return sneakyThrow(failure);
            }
            if (failure != null) {
                return sneakyThrow(failure);
            }
            ResponseBody error = ResponseBody.create(
                    MediaType.parse("application/json"),
                    "{\"error\":{\"code\":\"REFRESH_REJECTED\"}}"
            );
            return Response.error(status, error);
        }

        @Override
        public Object register(RegisterRequest body, Continuation<? super Response<AuthResponse>> continuation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object login(LoginRequest body, Continuation<? super Response<AuthResponse>> continuation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object logout(LogoutRequest body, Continuation<? super Response<Unit>> continuation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object logoutAll(Continuation<? super Response<Unit>> continuation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object changePassword(
                ChangePasswordRequest body,
                Continuation<? super Response<Unit>> continuation
        ) {
            throw new UnsupportedOperationException();
        }

        @SuppressWarnings("unchecked")
        private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
            throw (E) throwable;
        }
    }
}
