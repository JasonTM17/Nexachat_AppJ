package com.nexachat.core.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.crypto.DeviceKeyStore.Identity;
import com.nexachat.core.crypto.E2eeCrypto.EncryptedMessage;
import com.nexachat.core.crypto.E2eeCrypto.KeyPair;
import com.nexachat.core.network.E2eeApi;
import com.nexachat.core.network.dto.E2eeDeviceDtos.ConversationE2eeDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.DeviceDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import com.nexachat.core.network.dto.E2eeEnvelopeDto;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.subjects.MaybeSubject;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Device-session behavior: registration on session availability, the
 * conflict-regeneration rule, device-list caching, and the no-identity decrypt
 * path. Network is mocked at the Retrofit {@link Call} boundary.
 */
public final class E2eeSessionTest {

    private E2eeApi api;
    private DeviceKeyStore keyStore;
    private TokenStore tokenStore;
    private E2eeSession session;

    @Before
    public void setUp() {
        api = mock(E2eeApi.class);
        keyStore = mock(DeviceKeyStore.class);
        tokenStore = mock(TokenStore.class);
        session = new E2eeSession(api, keyStore, tokenStore, new Moshi.Builder().build());
    }

    @Test
    public void registersStoredIdentityOnSessionAvailable() throws Exception {
        Identity identity = new Identity("dev-1", new byte[32], new byte[32]);
        when(keyStore.identity("u1")).thenReturn(identity);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        Call<DeviceDto> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(new DeviceDto()));
        when(api.registerDevice(anyString(), any())).thenReturn(call);

        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        verify(keyStore).rememberCurrentUser("u1");
        verify(api).registerDevice(org.mockito.ArgumentMatchers.eq("dev-1"), any());
    }

    @Test
    public void conflictRegeneratesIdentityOnce() throws Exception {
        Identity stored = new Identity("dev-old", new byte[32], new byte[32]);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        // After clear() the next lookup misses, so ensureIdentity mints a fresh
        // pair under the mocked dev-new id.
        when(keyStore.identity("u1")).thenReturn(stored, (Identity) null);
        when(keyStore.deviceId("u1")).thenReturn("dev-new");
        Call<DeviceDto> conflict = mock(Call.class);
        when(conflict.execute()).thenReturn(Response.error(409, ResponseBody.create(
                MediaType.get("application/json"),
                "{\"error\":{\"code\":\"E2EE_DEVICE_CONFLICT\"}}")));
        Call<DeviceDto> ok = mock(Call.class);
        when(ok.execute()).thenReturn(Response.success(new DeviceDto()));
        when(api.registerDevice(org.mockito.ArgumentMatchers.eq("dev-old"), any())).thenReturn(conflict);
        when(api.registerDevice(org.mockito.ArgumentMatchers.eq("dev-new"), any())).thenReturn(ok);

        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        verify(keyStore).clear("u1");
        verify(api).registerDevice(org.mockito.ArgumentMatchers.eq("dev-new"), any());
    }

    @Test
    public void e2eeStateCachesUntilInvalidated() throws Exception {
        ConversationE2eeDto dto = new ConversationE2eeDto(true, java.util.List.of());
        Call<ConversationE2eeDto> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(dto));
        when(call.clone()).thenReturn(call);
        when(api.getE2ee("c1")).thenReturn(call);

        session.e2eeState("c1").test().awaitDone(5, TimeUnit.SECONDS).assertValue(dto);
        session.e2eeState("c1").test().awaitDone(5, TimeUnit.SECONDS).assertValue(dto);
        verify(api, org.mockito.Mockito.times(1)).getE2ee("c1");

        session.invalidateDevices("c1");
        session.e2eeState("c1").test().awaitDone(5, TimeUnit.SECONDS).assertValue(dto);
        verify(api, org.mockito.Mockito.times(2)).getE2ee("c1");
    }

    @Test
    public void decryptNowWithoutIdentityReturnsEmpty() {
        when(keyStore.currentUserId()).thenReturn("u1");
        when(keyStore.identity("u1")).thenReturn(null);

        assertTrue(session.decryptNow("c1", "u2", "m1", "e2ee1:abc", java.util.List.of()).isEmpty());
    }

    @Test
    public void decryptNowWithIdentityAndUnknownEnvelopeReturnsEmpty()
            throws java.security.GeneralSecurityException {
        byte[] priv = E2eeCrypto.generateDeviceKeyPair().privateKey();
        Identity identity = new Identity("dev-1",
                E2eeCrypto.publicFromPrivate(priv), priv);
        when(keyStore.currentUserId()).thenReturn("u1");
        when(keyStore.identity("u1")).thenReturn(identity);

        assertFalse(session.decryptNow("c1", "u2", "m1", "e2ee1:abc",
                java.util.List.of()).isPresent());
    }

    @Test
    public void toggleSurfacesServerErrorsAsE2eeException() throws Exception {
        Call<ConversationE2eeDto> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.error(409, ResponseBody.create(
                MediaType.get("application/json"),
                "{\"error\":{\"code\":\"E2EE_UNSUPPORTED_CONVERSATION\"}}")));
        when(api.setE2ee(org.mockito.ArgumentMatchers.eq("c9"), any())).thenReturn(call);

        session.setE2ee("c9", true).test().awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_UNSUPPORTED_CONVERSATION".equals(error.getMessage()));
    }

    @Test
    public void revokeIgnoresNetworkFailureButStillWipes() throws Exception {
        Identity identity = new Identity("dev-1", new byte[32], new byte[32]);
        when(keyStore.identity("u1")).thenReturn(identity);
        Call<Void> call = mock(Call.class);
        when(call.execute()).thenThrow(new IOException("offline"));
        when(api.revokeDevice("dev-1")).thenReturn(call);

        session.revokeCurrentDevice("u1").test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        verify(keyStore).clear("u1");
        verify(keyStore).forgetCurrentUser();
    }

    @Test
    public void currentDeviceIdIsReadOnly() {
        when(keyStore.currentUserId()).thenReturn("u1");
        when(keyStore.storedDeviceId("u1")).thenReturn(null);

        org.junit.Assert.assertNull(session.currentDeviceId());
        verify(keyStore, never()).deviceId(anyString());
    }

    // ---- Registration outcome rules (bounded retries, terminal 4xx) ----

    @Test
    public void networkErrorThenSuccessRetriesOnce() throws Exception {
        stubIdentityAndTokens();
        Call<DeviceDto> dead = failingCall(new IOException("offline"));
        Call<DeviceDto> ok = okCall();
        when(api.registerDevice(eq("dev-1"), any())).thenReturn(dead, ok);
        TestScheduler scheduler = new TestScheduler();
        E2eeSession timed = timedSession(scheduler);

        TestObserver<Void> observer = timed.onSessionAvailable().test();
        verify(api, timeout(3_000).times(1)).registerDevice(eq("dev-1"), any());
        advanceUntilRegisterCount(scheduler, 2);
        observer.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        verify(api, times(2)).registerDevice(eq("dev-1"), any());
    }

    @Test
    public void networkErrorsFailAfterThreeTotalAttempts() throws Exception {
        stubIdentityAndTokens();
        Call<DeviceDto> dead = failingCall(new IOException("down"));
        when(api.registerDevice(eq("dev-1"), any())).thenReturn(dead);
        TestScheduler scheduler = new TestScheduler();
        E2eeSession timed = timedSession(scheduler);

        TestObserver<Void> observer = timed.onSessionAvailable().test();
        verify(api, timeout(3_000).times(1)).registerDevice(eq("dev-1"), any());
        advanceUntilRegisterCount(scheduler, 2);
        advanceUntilRegisterCount(scheduler, 3);
        observer.awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_NETWORK".equals(error.getMessage()));
        verify(api, times(3)).registerDevice(eq("dev-1"), any());
    }

    @Test
    public void serverErrorRetriesThenSucceeds() throws Exception {
        stubIdentityAndTokens();
        Call<DeviceDto> down = mock(Call.class);
        when(down.execute()).thenReturn(Response.error(503, ResponseBody.create(
                MediaType.get("application/json"), "{}")));
        Call<DeviceDto> ok = okCall();
        when(api.registerDevice(eq("dev-1"), any())).thenReturn(down, ok);
        TestScheduler scheduler = new TestScheduler();
        E2eeSession timed = timedSession(scheduler);

        TestObserver<Void> observer = timed.onSessionAvailable().test();
        verify(api, timeout(3_000).times(1)).registerDevice(eq("dev-1"), any());
        advanceUntilRegisterCount(scheduler, 2);
        observer.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        verify(api, times(2)).registerDevice(eq("dev-1"), any());
    }

    @Test
    public void clientErrorIsTerminalWithoutRetry() throws Exception {
        stubIdentityAndTokens();
        Call<DeviceDto> limit = mock(Call.class);
        when(limit.execute()).thenReturn(Response.error(409, ResponseBody.create(
                MediaType.get("application/json"),
                "{\"error\":{\"code\":\"E2EE_DEVICE_LIMIT\"}}")));
        when(api.registerDevice(eq("dev-1"), any())).thenReturn(limit);

        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_DEVICE_LIMIT".equals(error.getMessage()));
        verify(api, times(1)).registerDevice(eq("dev-1"), any());
    }

    @Test
    public void secondConflictIsTerminalAndRegeneratesOnce() throws Exception {
        Identity stored = new Identity("dev-old", new byte[32], new byte[32]);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        when(keyStore.identity("u1")).thenReturn(stored, (Identity) null);
        when(keyStore.deviceId("u1")).thenReturn("dev-new");
        Call<DeviceDto> conflictOld = conflictCall();
        Call<DeviceDto> conflictNew = conflictCall();
        when(api.registerDevice(eq("dev-old"), any())).thenReturn(conflictOld);
        when(api.registerDevice(eq("dev-new"), any())).thenReturn(conflictNew);

        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_DEVICE_CONFLICT".equals(error.getMessage()));
        verify(keyStore, times(1)).clear("u1");
        verify(keyStore, times(1)).deviceId("u1");
        verify(api, times(1)).registerDevice(eq("dev-new"), any());
    }

    @Test
    public void delayedConflictAfterWipeDoesNotRegenerate() throws Exception {
        Identity stored = new Identity("dev-old", new byte[32], new byte[32]);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        when(keyStore.currentUserId()).thenReturn("u1");
        when(keyStore.identity("u1")).thenReturn(stored, (Identity) null);
        when(keyStore.deviceId("u1")).thenReturn("dev-new");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Call<DeviceDto> conflict = mock(Call.class);
        when(conflict.execute()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Response.error(409, ResponseBody.create(
                    MediaType.get("application/json"),
                    "{\"error\":{\"code\":\"E2EE_DEVICE_CONFLICT\"}}"));
        });
        when(api.registerDevice(eq("dev-old"), any())).thenReturn(conflict);

        TestObserver<Void> observer = session.onSessionAvailable().test();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        session.wipeLocal("u1");
        release.countDown();
        observer.awaitDone(5, TimeUnit.SECONDS).assertError(
                error -> error instanceof E2eeSession.E2eeException);
        verify(keyStore, times(1)).clear("u1");
        verify(keyStore, never()).deviceId("u1");
        verify(keyStore, never()).put(eq("u1"), anyString(), any());
        verify(api, times(1)).registerDevice(anyString(), any());
    }

    // ---- encryptFor: registration precondition + member-device coverage ----

    @Test
    public void encryptRegistersDeviceThenCoversAllMembers() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(new Identity("dev-me", mine.publicKey(), mine.privateKey()),
                twoMemberState(mine.publicKey(), peer.publicKey()));

        EncryptedMessage encrypted = session.encryptFor("c1", "xin chào", "u1", "msg-1")
                .test().awaitDone(5, TimeUnit.SECONDS).assertValueCount(1)
                .values().get(0);

        assertEquals(2, encrypted.envelopes().size());
        E2eeCrypto crypto = new E2eeCrypto();
        assertEquals("xin chào", crypto.decrypt(encrypted.content(),
                encrypted.envelopes(), "dev-me", mine.privateKey(), "c1", "u1", "msg-1")
                .orElseThrow(AssertionError::new));
        assertEquals("xin chào", crypto.decrypt(encrypted.content(),
                encrypted.envelopes(), "dev-peer", peer.privateKey(), "c1", "u1", "msg-1")
                .orElseThrow(AssertionError::new));
        verify(api, times(1)).registerDevice(eq("dev-me"), any());
    }

    @Test
    public void subsequentEncryptReusesConfirmedRegistration() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(new Identity("dev-me", mine.publicKey(), mine.privateKey()),
                twoMemberState(mine.publicKey(), peer.publicKey()));

        session.encryptFor("c1", "one", "u1", "m1").test()
                .awaitDone(5, TimeUnit.SECONDS).assertValueCount(1);
        session.encryptFor("c1", "two", "u1", "m2").test()
                .awaitDone(5, TimeUnit.SECONDS).assertValueCount(1);

        verify(api, times(1)).registerDevice(eq("dev-me"), any());
        verify(api, times(1)).getE2ee("c1");
    }

    @Test
    public void freshRegistrationInvalidatesCachedDevices() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        ConversationE2eeDto state = twoMemberState(mine.publicKey(), peer.publicKey());
        Call<ConversationE2eeDto> e2eeCall = stateCall(state);
        when(api.getE2ee("c1")).thenReturn(e2eeCall);
        session.e2eeState("c1").test().awaitDone(5, TimeUnit.SECONDS)
                .assertValueCount(1);
        verify(api, times(1)).getE2ee("c1");

        stubEncryptSession(new Identity("dev-me", mine.publicKey(), mine.privateKey()),
                state);
        session.encryptFor("c1", "hi", "u1", "m1").test()
                .awaitDone(5, TimeUnit.SECONDS).assertValueCount(1);
        // A fresh registration drops the pre-registration cache, so the member
        // list was fetched again before encrypting.
        verify(api, times(2)).getE2ee("c1");
    }

    @Test
    public void encryptFailsWhenConversationIsDisabled() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(new Identity("dev-me", mine.publicKey(), mine.privateKey()),
                new ConversationE2eeDto(false, List.of(
                        new MemberDeviceDto("u2", "dev-peer",
                                E2eeCrypto.b64url(peer.publicKey())))));

        session.encryptFor("c1", "hi", "u1", "m1").test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_NOT_ENABLED".equals(error.getMessage()));
    }

    @Test
    public void encryptFailsWhenOwnDeviceMissingFromMemberList() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(new Identity("dev-me", mine.publicKey(), mine.privateKey()),
                new ConversationE2eeDto(true, List.of(
                        new MemberDeviceDto("u2", "dev-peer",
                                E2eeCrypto.b64url(peer.publicKey())))));

        session.encryptFor("c1", "hi", "u1", "m1").test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_STALE_DEVICES".equals(error.getMessage()));
        // A missing own device triggers exactly one cache-invalidating refetch.
        verify(api, times(2)).getE2ee("c1");
    }

    @Test
    public void encryptFailsWhenOnlyOwnDeviceIsListed() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(new Identity("dev-me", mine.publicKey(), mine.privateKey()),
                new ConversationE2eeDto(true, List.of(
                        new MemberDeviceDto("u1", "dev-me",
                                E2eeCrypto.b64url(mine.publicKey())))));

        session.encryptFor("c1", "hi", "u1", "m1").test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_STALE_DEVICES".equals(error.getMessage()));
    }

    @Test
    public void encryptFailsWhenSenderDiffersFromTokenUser() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(new Identity("dev-me", mine.publicKey(), mine.privateKey()),
                new ConversationE2eeDto(true, List.of()));

        session.encryptFor("c1", "hi", "u2", "m1").test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException);
        verify(api, never()).registerDevice(anyString(), any());
        verify(api, never()).getE2ee(anyString());
    }

    @Test
    public void encryptFailsWhenRegistrationIsRejected() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        when(keyStore.identity("u1"))
                .thenReturn(new Identity("dev-me", mine.publicKey(), mine.privateKey()));
        Call<DeviceDto> rejected = mock(Call.class);
        when(rejected.execute()).thenReturn(Response.error(403, ResponseBody.create(
                MediaType.get("application/json"),
                "{\"error\":{\"code\":\"E2EE_DEVICE_LIMIT\"}}")));
        when(api.registerDevice(eq("dev-me"), any())).thenReturn(rejected);

        session.encryptFor("c1", "hi", "u1", "m1").test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException);
        verify(api, never()).getE2ee(anyString());
    }

    // ---- ensureIdentity serialization across concurrent first uses ----

    @Test
    public void concurrentEnsureIdentityMintsOneSharedIdentity() throws Exception {
        AtomicReference<Identity> stored = new AtomicReference<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        when(keyStore.identity("u1")).thenAnswer(invocation -> {
            if (stored.get() == null) {
                try {
                    barrier.await(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Once serialization holds, no second caller ever joins.
                }
            }
            return stored.get();
        });
        when(keyStore.deviceId("u1")).thenReturn("dev-shared");
        doAnswer(invocation -> {
            E2eeCrypto.KeyPair pair = invocation.getArgument(2);
            stored.set(new Identity(invocation.getArgument(1), pair.publicKey(),
                    pair.privateKey()));
            return null;
        }).when(keyStore).put(eq("u1"), anyString(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Identity> first = pool.submit(
                    () -> session.ensureIdentity("u1").blockingGet());
            Future<Identity> second = pool.submit(
                    () -> session.ensureIdentity("u1").blockingGet());
            Identity a = first.get(15, TimeUnit.SECONDS);
            Identity b = second.get(15, TimeUnit.SECONDS);
            assertEquals(a.getDeviceId(), b.getDeviceId());
            assertArrayEquals(a.getPublicKey(), b.getPublicKey());
        } finally {
            pool.shutdownNow();
        }
        verify(keyStore, times(1)).put(eq("u1"), anyString(), any());
    }

    // ---- Boundary guards: null envelope entries must not crash ingest ----

    @Test
    public void decryptNowSkipsNullEnvelopeEntries() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        when(keyStore.currentUserId()).thenReturn("u1");
        when(keyStore.identity("u1"))
                .thenReturn(new Identity("dev-me", mine.publicKey(), mine.privateKey()));

        List<E2eeEnvelopeDto> onlyNull = new ArrayList<>();
        onlyNull.add(null);
        assertTrue(session.decryptNow("c1", "u2", "m1", "e2ee1:abc", onlyNull)
                .isEmpty());

        EncryptedMessage wire = new E2eeCrypto().encrypt("bí mật", "c1", "u2", "m1",
                List.of(new E2eeCrypto.DevicePublicKey("dev-me", mine.publicKey())));
        List<E2eeEnvelopeDto> envelopes = new ArrayList<>();
        envelopes.add(null);
        envelopes.add(new E2eeEnvelopeDto(wire.envelopes().get(0).deviceId(),
                wire.envelopes().get(0).wrappedKey()));
        assertEquals("bí mật", session.decryptNow("c1", "u2", "m1",
                wire.content(), envelopes).orElseThrow(AssertionError::new));
    }

    // ---- Session-fence rework: wipe boundaries retire in-flight work ----

    @Test
    public void wipeDuringMemberFetchDropsResultAndCacheWrite() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Call<ConversationE2eeDto> blocked = mock(Call.class);
        ConversationE2eeDto state = new ConversationE2eeDto(true, List.of());
        when(blocked.execute()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Response.success(state);
        });
        Call<ConversationE2eeDto> fresh = stateCall(state);
        when(api.getE2ee("c1")).thenReturn(blocked, fresh);

        TestObserver<ConversationE2eeDto> first = session.e2eeState("c1").test();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        session.wipeLocal("u1");
        release.countDown();
        first.awaitDone(5, TimeUnit.SECONDS).assertError(
                error -> error instanceof E2eeSession.E2eeException);
        // The aborted response must not have populated the cache.
        session.e2eeState("c1").test().awaitDone(5, TimeUnit.SECONDS)
                .assertValue(state);
        verify(api, times(2)).getE2ee("c1");
    }

    @Test
    public void wipeDuringEncryptMemberFetchResetsSend() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        when(keyStore.identity("u1"))
                .thenReturn(new Identity("dev-me", mine.publicKey(), mine.privateKey()));
        Call<DeviceDto> ok = okCall();
        when(api.registerDevice(eq("dev-me"), any())).thenReturn(ok);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Call<ConversationE2eeDto> blocked = mock(Call.class);
        when(blocked.execute()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Response.success(
                    twoMemberState(mine.publicKey(), peer.publicKey()));
        });
        when(api.getE2ee("c1")).thenReturn(blocked);

        TestObserver<EncryptedMessage> observer =
                session.encryptFor("c1", "hi", "u1", "m1").test();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        session.wipeLocal("u1");
        release.countDown();
        observer.awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_SESSION_RESET".equals(error.getMessage()))
                .assertNoValues();
    }

    @Test
    public void wipeDuringBackoffSuppressesRetryCall() throws Exception {
        stubIdentityAndTokens();
        Call<DeviceDto> dead = failingCall(new IOException("offline"));
        when(api.registerDevice(eq("dev-1"), any())).thenReturn(dead);
        TestScheduler scheduler = new TestScheduler();
        E2eeSession timed = timedSession(scheduler);

        TestObserver<Void> observer = timed.onSessionAvailable().test();
        verify(api, timeout(3_000).times(1)).registerDevice(eq("dev-1"), any());
        timed.wipeLocal("u1");
        scheduler.advanceTimeBy(10, TimeUnit.SECONDS);
        // The delayed retry was armed before the wipe; it must not fire.
        verify(api, times(1)).registerDevice(eq("dev-1"), any());
        observer.assertError(error -> error instanceof E2eeSession.E2eeException);
    }

    @Test
    public void sameUserRefreshDoesNotRetireInFlightRegistration() throws Exception {
        stubIdentityAndTokens();
        when(keyStore.currentUserId()).thenReturn("u1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Call<DeviceDto> blocked = mock(Call.class);
        when(blocked.execute()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Response.success(new DeviceDto());
        });
        Call<DeviceDto> ok = okCall();
        when(api.registerDevice(eq("dev-1"), any())).thenReturn(blocked, ok);

        TestObserver<Void> first = session.onSessionAvailable().test();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        // A same-user session refresh is not an account boundary: it joins the
        // shared in-flight registration instead of invalidating it or
        // re-registering.
        TestObserver<Void> second = session.onSessionAvailable().test();
        release.countDown();
        second.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        first.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        // Once confirmed, another same-user boundary stays memoized.
        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS)
                .assertComplete();
        verify(api, times(1)).registerDevice(eq("dev-1"), any());
    }

    @Test
    public void delayedRegistrationSuccessAfterWipeCannotPublishProof() throws Exception {
        stubIdentityAndTokens();
        when(keyStore.currentUserId()).thenReturn("u1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Call<DeviceDto> blocked = mock(Call.class);
        when(blocked.execute()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Response.success(new DeviceDto());
        });
        Call<DeviceDto> retry = okCall();
        when(api.registerDevice(eq("dev-1"), any())).thenReturn(blocked, retry);

        TestObserver<Void> first = session.onSessionAvailable().test();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        session.wipeLocal("u1");
        release.countDown();
        first.awaitDone(5, TimeUnit.SECONDS).assertError(
                error -> error instanceof E2eeSession.E2eeException);
        // The stale success could not mark readiness: the next boundary
        // registers again instead of skipping the call.
        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS)
                .assertComplete();
        verify(api, times(2)).registerDevice(eq("dev-1"), any());
    }

    @Test
    public void delayedRevokeDoesNotForgetNewerAccount() throws Exception {
        AtomicReference<String> current = new AtomicReference<>("uA");
        when(keyStore.currentUserId()).thenAnswer(inv -> current.get());
        doAnswer(inv -> {
            current.set(inv.getArgument(0));
            return null;
        }).when(keyStore).rememberCurrentUser(anyString());
        doAnswer(inv -> {
            current.set(null);
            return null;
        }).when(keyStore).forgetCurrentUser();
        when(keyStore.identity("uA"))
                .thenReturn(new Identity("dev-a", new byte[32], new byte[32]));
        when(keyStore.identity("uB"))
                .thenReturn(new Identity("dev-b", new byte[32], new byte[32]));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("uB")));
        Call<DeviceDto> ok = okCall();
        when(api.registerDevice(eq("dev-b"), any())).thenReturn(ok);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Call<Void> revokeCall = mock(Call.class);
        when(revokeCall.execute()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Response.success(null);
        });
        when(api.revokeDevice("dev-a")).thenReturn(revokeCall);

        TestObserver<Void> revoke = session.revokeCurrentDevice("uA").test();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS)
                .assertComplete();
        release.countDown();
        revoke.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        assertEquals("uB", current.get());
        verify(keyStore, never()).clear("uB");
    }

    @Test
    public void staleSetE2eeResponseIsRejected() throws Exception {
        ConversationE2eeDto state = new ConversationE2eeDto(true, List.of());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Call<ConversationE2eeDto> blocked = mock(Call.class);
        when(blocked.execute()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Response.success(state);
        });
        when(api.setE2ee(eq("c1"), any())).thenReturn(blocked);

        TestObserver<ConversationE2eeDto> observer = session.setE2ee("c1", true).test();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        session.wipeLocal("u1");
        release.countDown();
        observer.awaitDone(5, TimeUnit.SECONDS).assertError(
                error -> error instanceof E2eeSession.E2eeException)
                .assertNoValues();
    }

    @Test
    public void encryptFailsOnNullMemberDeviceEntries() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        List<MemberDeviceDto> devices = new ArrayList<>();
        devices.add(null);
        devices.add(new MemberDeviceDto("u2", "dev-peer",
                E2eeCrypto.b64url(peer.publicKey())));
        devices.add(new MemberDeviceDto("u1", "dev-me",
                E2eeCrypto.b64url(mine.publicKey())));
        stubEncryptSession(new Identity("dev-me", mine.publicKey(), mine.privateKey()),
                new ConversationE2eeDto(true, devices));

        session.encryptFor("c1", "hi", "u1", "m1").test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_STALE_DEVICES".equals(error.getMessage()));
    }

    // ---- Unified call budget across a logical registration ----

    @Test
    public void serverErrorThenConflictThenServerErrorExhaustsBudget() throws Exception {
        Identity stored = new Identity("dev-old", new byte[32], new byte[32]);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        when(keyStore.identity("u1")).thenReturn(stored, (Identity) null);
        when(keyStore.deviceId("u1")).thenReturn("dev-new");
        Call<DeviceDto> first503 = errorCall(503);
        Call<DeviceDto> conflict = conflictCall();
        Call<DeviceDto> second503 = errorCall(503);
        when(api.registerDevice(eq("dev-old"), any())).thenReturn(first503, conflict);
        when(api.registerDevice(eq("dev-new"), any())).thenReturn(second503);
        TestScheduler scheduler = new TestScheduler();
        E2eeSession timed = timedSession(scheduler);

        TestObserver<Void> observer = timed.onSessionAvailable().test();
        verify(api, timeout(3_000).times(1)).registerDevice(eq("dev-old"), any());
        advanceUntilRegisterCount(scheduler, 2);
        advanceUntilRegisterCount(scheduler, 3);
        observer.awaitDone(5, TimeUnit.SECONDS).assertError(
                error -> error instanceof E2eeSession.E2eeException);
        // One shared budget for the whole logical registration: 3 calls total.
        verify(api, times(3)).registerDevice(anyString(), any());
    }

    @Test
    public void conflictOnFinalAttemptDoesNotFireFourthCall() throws Exception {
        stubIdentityAndTokens();
        Call<DeviceDto> first503 = errorCall(503);
        Call<DeviceDto> second503 = errorCall(503);
        Call<DeviceDto> conflict = conflictCall();
        Call<DeviceDto> fourth = okCall();
        when(api.registerDevice(eq("dev-1"), any()))
                .thenReturn(first503, second503, conflict, fourth);
        TestScheduler scheduler = new TestScheduler();
        E2eeSession timed = timedSession(scheduler);

        TestObserver<Void> observer = timed.onSessionAvailable().test();
        verify(api, timeout(3_000).times(1)).registerDevice(eq("dev-1"), any());
        advanceUntilRegisterCount(scheduler, 2);
        advanceUntilRegisterCount(scheduler, 3);
        observer.awaitDone(5, TimeUnit.SECONDS).assertError(
                error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_DEVICE_CONFLICT".equals(error.getMessage()));
        verify(api, times(3)).registerDevice(anyString(), any());
        verify(keyStore, never()).deviceId("u1");
    }

    private static Call<DeviceDto> errorCall(int status) throws IOException {
        Call<DeviceDto> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.error(status, ResponseBody.create(
                MediaType.get("application/json"), "{}")));
        return call;
    }


    // ---- Capture-at-subscription + single-flight registration ----

    @Test
    public void delayedTokensAfterAccountSwitchAreDead() throws Exception {
        AtomicReference<String> current = new AtomicReference<>("uA");
        when(keyStore.currentUserId()).thenAnswer(inv -> current.get());
        doAnswer(inv -> {
            current.set(inv.getArgument(0));
            return null;
        }).when(keyStore).rememberCurrentUser(anyString());
        doAnswer(inv -> {
            current.set(null);
            return null;
        }).when(keyStore).forgetCurrentUser();
        when(keyStore.identity("uA"))
                .thenReturn(new Identity("dev-a", new byte[32], new byte[32]));
        when(keyStore.identity("uB"))
                .thenReturn(new Identity("dev-b", new byte[32], new byte[32]));
        MaybeSubject<Tokens> delayed = MaybeSubject.create();
        when(tokenStore.readTokens())
                .thenReturn(delayed, Maybe.just(tokensFor("uB")));
        Call<DeviceDto> ok = okCall();
        when(api.registerDevice(eq("dev-a"), any())).thenReturn(ok);
        when(api.registerDevice(eq("dev-b"), any())).thenReturn(ok);

        TestObserver<Void> stale = session.onSessionAvailable().test();
        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS)
                .assertComplete();
        delayed.onSuccess(tokensFor("uA"));
        stale.awaitDone(5, TimeUnit.SECONDS).assertError(
                error -> error instanceof E2eeSession.E2eeException);
        // The stale tokens never re-remembered the old account nor registered.
        verify(keyStore, never()).rememberCurrentUser("uA");
        verify(api, times(1)).registerDevice(anyString(), any());
        assertEquals("uB", current.get());
    }

    @Test
    public void delayedTokensAfterWipeCannotStartEncrypt() throws Exception {
        when(keyStore.identity("u1"))
                .thenReturn(new Identity("dev-1", new byte[32], new byte[32]));
        MaybeSubject<Tokens> delayed = MaybeSubject.create();
        when(tokenStore.readTokens()).thenReturn(delayed);
        Call<DeviceDto> ok = okCall();
        when(api.registerDevice(eq("dev-1"), any())).thenReturn(ok);

        TestObserver<EncryptedMessage> observer =
                session.encryptFor("c1", "hi", "u1", "m1").test();
        session.wipeLocal("u1");
        delayed.onSuccess(tokensFor("u1"));
        observer.awaitDone(5, TimeUnit.SECONDS).assertError(error -> {
            System.err.println("WIPE-ENCRYPT-ERR=" + error);
            return error instanceof E2eeSession.E2eeException;
        });
        verify(api, never()).registerDevice(anyString(), any());
        verify(keyStore, never()).rememberCurrentUser(anyString());
    }

    @Test
    public void concurrentRegistrationsShareOneFlightAndPair() throws Exception {
        E2eeCrypto.KeyPair oldPair = E2eeCrypto.generateDeviceKeyPair();
        E2eeCrypto.KeyPair peerPair = E2eeCrypto.generateDeviceKeyPair();
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        // Stateful keystore fake so regeneration clears and re-mints for real.
        AtomicReference<Identity> stored = new AtomicReference<>(
                new Identity("dev-1", oldPair.publicKey(), oldPair.privateKey()));
        when(keyStore.identity("u1")).thenAnswer(inv -> stored.get());
        doAnswer(inv -> {
            stored.set(null);
            return null;
        }).when(keyStore).clear("u1");
        doAnswer(inv -> {
            String deviceId = inv.getArgument(1);
            E2eeCrypto.KeyPair pair = inv.getArgument(2);
            stored.set(new Identity(deviceId, pair.publicKey(), pair.privateKey()));
            return null;
        }).when(keyStore).put(eq("u1"), anyString(), any());
        when(keyStore.deviceId("u1")).thenReturn("dev-new");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Call<DeviceDto> conflict = mock(Call.class);
        when(conflict.execute()).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Response.error(409, ResponseBody.create(
                    MediaType.get("application/json"),
                    "{\"error\":{\"code\":\"E2EE_DEVICE_CONFLICT\"}}"));
        });
        Call<DeviceDto> freshOk = okCall();
        when(api.registerDevice(eq("dev-1"), any())).thenReturn(conflict);
        when(api.registerDevice(eq("dev-new"), any())).thenReturn(freshOk);
        Call<ConversationE2eeDto> stateCall = mock(Call.class);
        when(stateCall.execute()).thenAnswer(inv -> Response.success(
                new ConversationE2eeDto(true, List.of(
                        new MemberDeviceDto("u1", "dev-new",
                                E2eeCrypto.b64url(stored.get().getPublicKey())),
                        new MemberDeviceDto("u2", "dev-peer",
                                E2eeCrypto.b64url(peerPair.publicKey()))))));
        when(api.getE2ee("c1")).thenReturn(stateCall);

        TestObserver<EncryptedMessage> first =
                session.encryptFor("c1", "m1", "u1", "id-1").test();
        TestObserver<EncryptedMessage> second =
                session.encryptFor("c1", "m2", "u1", "id-2").test();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        release.countDown();
        first.awaitDone(5, TimeUnit.SECONDS).assertValueCount(1);
        second.awaitDone(5, TimeUnit.SECONDS).assertValueCount(1);
        // One logical registration: one original call, one rotation, one fresh call.
        verify(api, times(1)).registerDevice(eq("dev-1"), any());
        verify(keyStore, times(1)).deviceId("u1");
        verify(api, times(1)).registerDevice(eq("dev-new"), any());
        // Both ciphertexts unwrap with the single stored-down pair.
        E2eeCrypto crypto = new E2eeCrypto();
        EncryptedMessage m1 = first.values().get(0);
        EncryptedMessage m2 = second.values().get(0);
        assertEquals("m1", crypto.decrypt(m1.content(), m1.envelopes(),
                stored.get().getDeviceId(), stored.get().getPrivateKey(),
                "c1", "u1", "id-1").orElseThrow(AssertionError::new));
        assertEquals("m2", crypto.decrypt(m2.content(), m2.envelopes(),
                stored.get().getDeviceId(), stored.get().getPrivateKey(),
                "c1", "u1", "id-2").orElseThrow(AssertionError::new));
    }

    @Test
    public void delayedRevokeDoesNotClearFreshIdentity() throws Exception {
        AtomicReference<String> current = new AtomicReference<>("uA");
        when(keyStore.currentUserId()).thenAnswer(inv -> current.get());
        doAnswer(inv -> {
            current.set(inv.getArgument(0));
            return null;
        }).when(keyStore).rememberCurrentUser(anyString());
        doAnswer(inv -> {
            current.set(null);
            return null;
        }).when(keyStore).forgetCurrentUser();
        Identity oldIdentity = new Identity("dev-a", new byte[32], new byte[32]);
        when(keyStore.identity("uA")).thenReturn(oldIdentity, (Identity) null);
        when(keyStore.deviceId("uA")).thenReturn("dev-a2");
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("uA")));
        Call<DeviceDto> ok = okCall();
        when(api.registerDevice(eq("dev-a2"), any())).thenReturn(ok);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Call<Void> revokeCall = mock(Call.class);
        when(revokeCall.execute()).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Response.success(null);
        });
        when(api.revokeDevice("dev-a")).thenReturn(revokeCall);

        TestObserver<Void> revoke = session.revokeCurrentDevice("uA").test();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        // Same user signs back in while the DELETE is still in flight: a
        // fresh identity is minted and registered under the new boundary.
        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS)
                .assertComplete();
        release.countDown();
        revoke.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        // The only local clear ran at revoke start, before the fresh mint;
        // the late DELETE performs no local mutation.
        InOrder order = inOrder(keyStore);
        order.verify(keyStore).clear("uA");
        order.verify(keyStore).deviceId("uA");
        verify(keyStore, times(1)).clear("uA");
        assertEquals("uA", current.get());
    }

    @Test
    public void wipeLocalForOtherAccountPreservesCurrentSession() throws Exception {
        AtomicReference<String> current = new AtomicReference<>("uB");
        when(keyStore.currentUserId()).thenAnswer(inv -> current.get());
        doAnswer(inv -> {
            current.set(inv.getArgument(0));
            return null;
        }).when(keyStore).rememberCurrentUser(anyString());
        doAnswer(inv -> {
            current.set(null);
            return null;
        }).when(keyStore).forgetCurrentUser();
        when(keyStore.identity("uB"))
                .thenReturn(new Identity("dev-b", new byte[32], new byte[32]));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("uB")));
        Call<DeviceDto> ok = okCall();
        when(api.registerDevice(eq("dev-b"), any())).thenReturn(ok);
        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS)
                .assertComplete();

        session.wipeLocal("uA");
        verify(keyStore).clear("uA");
        verify(keyStore, never()).clear("uB");
        verify(keyStore, never()).forgetCurrentUser();
        assertEquals("uB", current.get());
        // B's readiness survives: a same-user boundary stays memoized.
        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS)
                .assertComplete();
        verify(api, times(1)).registerDevice(anyString(), any());
    }

    // ---- helpers ----

    /**
     * Drives the test scheduler in small steps until the device registration
     * has been attempted {@code expected} times (or a real-time deadline),
     * absorbing the race between the delayed retry being scheduled on the IO
     * worker and virtual time advancing.
     */


    private void advanceUntilRegisterCount(TestScheduler scheduler, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            scheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS);
            try {
                verify(api, times(expected)).registerDevice(anyString(), any());
                return;
            } catch (AssertionError notYet) {
                Thread.sleep(10);
            }
        }
        verify(api, times(expected)).registerDevice(anyString(), any());
    }

    private E2eeSession timedSession(TestScheduler scheduler) {
        return new E2eeSession(api, keyStore, tokenStore, new Moshi.Builder().build(),
                new E2eeCrypto(), scheduler);
    }

    private void stubIdentityAndTokens() {
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        when(keyStore.identity("u1"))
                .thenReturn(new Identity("dev-1", new byte[32], new byte[32]));
    }

    private void stubEncryptSession(Identity identity, ConversationE2eeDto e2eeState)
            throws Exception {
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        when(keyStore.identity("u1")).thenReturn(identity);
        Call<DeviceDto> ok = okCall();
        Call<ConversationE2eeDto> state = stateCall(e2eeState);
        when(api.registerDevice(eq(identity.getDeviceId()), any())).thenReturn(ok);
        when(api.getE2ee("c1")).thenReturn(state);
    }

    private static ConversationE2eeDto twoMemberState(byte[] minePublic,
            byte[] peerPublic) {
        return new ConversationE2eeDto(true, List.of(
                new MemberDeviceDto("u1", "dev-me", E2eeCrypto.b64url(minePublic)),
                new MemberDeviceDto("u2", "dev-peer", E2eeCrypto.b64url(peerPublic))));
    }

    private static Call<DeviceDto> okCall() throws IOException {
        Call<DeviceDto> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(new DeviceDto()));
        return call;
    }

    private static Call<DeviceDto> failingCall(IOException failure) throws IOException {
        Call<DeviceDto> call = mock(Call.class);
        when(call.execute()).thenThrow(failure);
        return call;
    }

    private static Call<DeviceDto> conflictCall() throws IOException {
        Call<DeviceDto> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.error(409, ResponseBody.create(
                MediaType.get("application/json"),
                "{\"error\":{\"code\":\"E2EE_DEVICE_CONFLICT\"}}")));
        return call;
    }

    private static Call<ConversationE2eeDto> stateCall(ConversationE2eeDto state)
            throws IOException {
        Call<ConversationE2eeDto> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(state));
        return call;
    }

    private static Tokens tokensFor(String userId) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        return new Tokens("header." + payload + ".sig", 0L, "refresh");
    }
}
