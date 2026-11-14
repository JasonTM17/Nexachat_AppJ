package com.nexachat.core.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
