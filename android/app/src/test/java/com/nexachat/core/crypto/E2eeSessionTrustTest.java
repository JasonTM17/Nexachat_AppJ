package com.nexachat.core.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.crypto.DeviceKeyStore.Identity;
import com.nexachat.core.crypto.E2eeCrypto.KeyPair;
import com.nexachat.core.database.InMemoryKeysetTrustDao;
import com.nexachat.core.network.E2eeApi;
import com.nexachat.core.network.dto.E2eeDeviceDtos.ConversationE2eeDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.DeviceDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.MemberDeviceDto;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Verified-keyset enforcement on the send path: an unverified direct roster
 * still encrypts (labelled trust on first use), a verified roster that grows
 * an attacker device fails {@code E2EE_KEYSET_CHANGED} before any crypto work,
 * and non-direct rosters keep their previous behavior.
 */
public final class E2eeSessionTrustTest {

    private static final String USER_A = "11111111-1111-1111-1111-111111111111";
    private static final String USER_B = "22222222-2222-2222-2222-222222222222";
    private static final String USER_C = "88888888-8888-8888-8888-888888888888";
    private static final String CONV = "33333333-3333-3333-3333-333333333333";
    private static final String DEV_ME = "44444444-4444-4444-4444-444444444444";
    private static final String DEV_PEER = "55555555-5555-5555-5555-555555555555";
    private static final String DEV_EXTRA = "77777777-7777-7777-7777-777777777777";

    private E2eeApi api;
    private DeviceKeyStore keyStore;
    private TokenStore tokenStore;
    private InMemoryKeysetTrustDao trustDao;
    private KeysetTrustStore trustStore;
    private E2eeCrypto crypto;
    private E2eeSession session;

    @Before
    public void setUp() {
        api = mock(E2eeApi.class);
        keyStore = mock(DeviceKeyStore.class);
        tokenStore = mock(TokenStore.class);
        trustDao = spy(new InMemoryKeysetTrustDao());
        trustStore = new KeysetTrustStore(trustDao);
        crypto = spy(new E2eeCrypto());
        session = new E2eeSession(api, keyStore, tokenStore, new Moshi.Builder().build(),
                crypto, trustStore);
    }

    @Test
    public void unverifiedDirectRosterStillEncrypts() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(mine, twoMemberState(mine, peer));

        session.encryptFor(CONV, "hi", USER_A, "m1").test()
                .awaitDone(5, TimeUnit.SECONDS).assertValueCount(1);
        assertEquals(2, session.encryptFor(CONV, "again", USER_A, "m2").blockingGet()
                .envelopes().size());
    }

    @Test
    public void verifiedRosterMatchEncrypts() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(mine, twoMemberState(mine, peer));

        byte[] shown = session.currentTranscript(CONV).blockingGet();
        session.markVerified(CONV, shown).blockingAwait();

        session.encryptFor(CONV, "hi", USER_A, "m1").test()
                .awaitDone(5, TimeUnit.SECONDS).assertValueCount(1);
    }

    /**
     * The falsifier: both users verified the genuine roster, then the server
     * serves the same roster plus an attacker device. The send must fail
     * before the attacker key ever reaches the crypto layer.
     */
    @Test
    public void attackerDeviceAfterVerifyErrorsBeforeCrypto() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        KeyPair attacker = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(mine, twoMemberState(mine, peer));

        byte[] shown = session.currentTranscript(CONV).blockingGet();
        session.markVerified(CONV, shown).blockingAwait();

        Call<ConversationE2eeDto> poisoned = stateCall(
                threeDeviceState(mine, peer, attacker));
        when(api.getE2ee(CONV)).thenReturn(poisoned);
        session.invalidateDevices(CONV);

        session.encryptFor(CONV, "hi", USER_A, "m1").test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_KEYSET_CHANGED".equals(error.getMessage()));
        verify(crypto, never()).encrypt(anyString(), anyString(), anyString(),
                anyString(), anyList());
        KeysetTrustStore.Record record = trustStore.state(USER_A, CONV).blockingGet()
                .orElseThrow(AssertionError::new);
        assertEquals(KeysetTrustStore.TrustState.CHANGED, record.state());
        assertNotNull(record.changedDigestHex());
    }

    @Test
    public void changedTrustSurvivesSessionRestart() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        KeyPair attacker = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(mine, twoMemberState(mine, peer));
        byte[] shown = session.currentTranscript(CONV).blockingGet();
        session.markVerified(CONV, shown).blockingAwait();
        Call<ConversationE2eeDto> poisoned = stateCall(
                threeDeviceState(mine, peer, attacker));
        when(api.getE2ee(CONV)).thenReturn(poisoned);
        session.invalidateDevices(CONV);
        session.encryptFor(CONV, "hi", USER_A, "m1").test()
                .awaitDone(5, TimeUnit.SECONDS).assertError(
                        error -> error instanceof E2eeSession.E2eeException);

        // A fresh session over the same trust table still refuses the send.
        E2eeSession restarted = new E2eeSession(api, keyStore, tokenStore,
                new Moshi.Builder().build(), crypto, new KeysetTrustStore(trustDao));
        restarted.encryptFor(CONV, "hi", USER_A, "m1").test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_KEYSET_CHANGED".equals(error.getMessage()));
        verify(crypto, never()).encrypt(anyString(), anyString(), anyString(),
                anyString(), anyList());
    }

    @Test
    public void oldRosterReturnStaysChanged() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        KeyPair attacker = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(mine, twoMemberState(mine, peer));
        byte[] shown = session.currentTranscript(CONV).blockingGet();
        session.markVerified(CONV, shown).blockingAwait();
        Call<ConversationE2eeDto> poisoned = stateCall(
                threeDeviceState(mine, peer, attacker));
        when(api.getE2ee(CONV)).thenReturn(poisoned);
        session.invalidateDevices(CONV);
        session.encryptFor(CONV, "hi", USER_A, "m1").test()
                .awaitDone(5, TimeUnit.SECONDS).assertError(
                        error -> error instanceof E2eeSession.E2eeException);

        // The attacker device disappearing does not clear the verdict.
        Call<ConversationE2eeDto> restored = stateCall(twoMemberState(mine, peer));
        when(api.getE2ee(CONV)).thenReturn(restored);
        session.invalidateDevices(CONV);
        session.encryptFor(CONV, "hi", USER_A, "m2").test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_KEYSET_CHANGED".equals(error.getMessage()));
        verify(crypto, never()).encrypt(anyString(), anyString(), anyString(),
                anyString(), anyList());
    }

    @Test
    public void reverificationClearsChanged() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        KeyPair attacker = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(mine, twoMemberState(mine, peer));
        byte[] shown = session.currentTranscript(CONV).blockingGet();
        session.markVerified(CONV, shown).blockingAwait();
        Call<ConversationE2eeDto> poisoned = stateCall(
                threeDeviceState(mine, peer, attacker));
        when(api.getE2ee(CONV)).thenReturn(poisoned);
        session.invalidateDevices(CONV);
        session.encryptFor(CONV, "hi", USER_A, "m1").test()
                .awaitDone(5, TimeUnit.SECONDS).assertError(
                        error -> error instanceof E2eeSession.E2eeException);

        // Comparing and approving the roster actually served lifts the pause.
        byte[] reshown = session.currentTranscript(CONV).blockingGet();
        session.markVerified(CONV, reshown).blockingAwait();
        session.encryptFor(CONV, "hi", USER_A, "m2").test()
                .awaitDone(5, TimeUnit.SECONDS).assertValueCount(1);
    }

    @Test
    public void markVerifiedBindsDisplayedBytesNotAFreshFetch() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        KeyPair attacker = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(mine, twoMemberState(mine, peer));
        byte[] shown = session.currentTranscript(CONV).blockingGet();

        // The roster shifts between display and tap: approval covers only
        // the bytes shown, so the new roster is detected as a change.
        Call<ConversationE2eeDto> poisoned = stateCall(
                threeDeviceState(mine, peer, attacker));
        when(api.getE2ee(CONV)).thenReturn(poisoned);
        session.invalidateDevices(CONV);
        session.markVerified(CONV, shown).blockingAwait();

        session.encryptFor(CONV, "hi", USER_A, "m1").test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_KEYSET_CHANGED".equals(error.getMessage()));
        verify(crypto, never()).encrypt(anyString(), anyString(), anyString(),
                anyString(), anyList());
    }

    @Test
    public void groupRosterBypassesTrustCheck() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        KeyPair third = E2eeCrypto.generateDeviceKeyPair();
        // A verified record exists for the conversation, but a non-direct
        // roster never consults it.
        trustStore.markVerified(USER_A, CONV, KeysetCheck.transcript(
                UUID.fromString(CONV), List.of(
                        new KeysetCheck.UserRoster(UUID.fromString(USER_A),
                                List.of(new KeysetCheck.Device(
                                        UUID.fromString(DEV_ME),
                                        KeysetCheck.ALGORITHM_X25519,
                                        mine.publicKey()))),
                        new KeysetCheck.UserRoster(UUID.fromString(USER_B),
                                List.of(new KeysetCheck.Device(
                                        UUID.fromString(DEV_PEER),
                                        KeysetCheck.ALGORITHM_X25519,
                                        peer.publicKey())))))).blockingAwait();
        stubEncryptSession(mine, new ConversationE2eeDto(true, List.of(
                new MemberDeviceDto(USER_A, DEV_ME, E2eeCrypto.b64url(mine.publicKey())),
                new MemberDeviceDto(USER_B, DEV_PEER, E2eeCrypto.b64url(peer.publicKey())),
                new MemberDeviceDto(USER_C, DEV_EXTRA, E2eeCrypto.b64url(third.publicKey())))));

        session.encryptFor(CONV, "hi", USER_A, "m1").test()
                .awaitDone(5, TimeUnit.SECONDS).assertValueCount(1);
        verify(trustDao, never()).checkAndMarkChanged(anyString(), anyString(),
                anyString(), anyLong());
    }

    @Test
    public void unparseableDirectRosterFailsClosed() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(mine, new ConversationE2eeDto(true, List.of(
                new MemberDeviceDto(USER_A, DEV_ME, E2eeCrypto.b64url(mine.publicKey())),
                new MemberDeviceDto(USER_B, "not-a-uuid",
                        E2eeCrypto.b64url(peer.publicKey())))));

        session.encryptFor(CONV, "hi", USER_A, "m1").test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_KEYSET_INVALID".equals(error.getMessage()));
        verify(crypto, never()).encrypt(anyString(), anyString(), anyString(),
                anyString(), anyList());
    }

    @Test
    public void currentTranscriptRejectsNonDirectAndDisabledRosters() throws Exception {
        KeyPair mine = E2eeCrypto.generateDeviceKeyPair();
        KeyPair peer = E2eeCrypto.generateDeviceKeyPair();
        KeyPair third = E2eeCrypto.generateDeviceKeyPair();
        stubEncryptSession(mine, new ConversationE2eeDto(true, List.of(
                new MemberDeviceDto(USER_A, DEV_ME, E2eeCrypto.b64url(mine.publicKey())),
                new MemberDeviceDto(USER_B, DEV_PEER, E2eeCrypto.b64url(peer.publicKey())),
                new MemberDeviceDto(USER_C, DEV_EXTRA, E2eeCrypto.b64url(third.publicKey())))));

        session.currentTranscript(CONV).test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_KEYSET_INVALID".equals(error.getMessage()));

        session.invalidateDevices(CONV);
        Call<ConversationE2eeDto> disabled = stateCall(new ConversationE2eeDto(false,
                List.of(new MemberDeviceDto(USER_A, DEV_ME,
                        E2eeCrypto.b64url(mine.publicKey())))));
        when(api.getE2ee(CONV)).thenReturn(disabled);
        session.currentTranscript(CONV).test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_NOT_ENABLED".equals(error.getMessage()));
    }

    // ---- helpers ----

    private void stubEncryptSession(KeyPair mine, ConversationE2eeDto e2eeState)
            throws Exception {
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor(USER_A)));
        when(keyStore.identity(USER_A)).thenReturn(
                new Identity(DEV_ME, mine.publicKey(), mine.privateKey()));
        Call<DeviceDto> ok = mock(Call.class);
        when(ok.execute()).thenReturn(Response.success(new DeviceDto()));
        when(api.registerDevice(eq(DEV_ME), any())).thenReturn(ok);
        Call<ConversationE2eeDto> state = stateCall(e2eeState);
        when(api.getE2ee(CONV)).thenReturn(state);
    }

    private static ConversationE2eeDto twoMemberState(KeyPair mine, KeyPair peer) {
        return new ConversationE2eeDto(true, List.of(
                new MemberDeviceDto(USER_A, DEV_ME, E2eeCrypto.b64url(mine.publicKey())),
                new MemberDeviceDto(USER_B, DEV_PEER, E2eeCrypto.b64url(peer.publicKey()))));
    }

    private static ConversationE2eeDto threeDeviceState(KeyPair mine, KeyPair peer,
            KeyPair attacker) {
        return new ConversationE2eeDto(true, List.of(
                new MemberDeviceDto(USER_A, DEV_ME, E2eeCrypto.b64url(mine.publicKey())),
                new MemberDeviceDto(USER_B, DEV_PEER, E2eeCrypto.b64url(peer.publicKey())),
                new MemberDeviceDto(USER_B, DEV_EXTRA,
                        E2eeCrypto.b64url(attacker.publicKey()))));
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
