package com.nexachat.core.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.database.InMemoryKeysetTrustDao;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

/**
 * Trust-record state machine over the durable DAO: first contact is
 * UNVERIFIED, verification pins the compared transcript's digest, a later
 * mismatch flips the record to CHANGED inside one transaction, and CHANGED
 * stays sticky until a fresh verification clears it.
 */
public final class KeysetTrustStoreTest {

    private static final String OWNER_A = "owner-a";
    private static final String OWNER_B = "owner-b";
    private static final String CONV = "33333333-3333-3333-3333-333333333333";

    private InMemoryKeysetTrustDao dao;
    private KeysetTrustStore store;

    @Before
    public void setUp() {
        dao = new InMemoryKeysetTrustDao();
        store = new KeysetTrustStore(dao);
    }

    @Test
    public void unverifiedThenVerifyThenMatch() throws Exception {
        byte[] transcript = baseTranscript();
        store.checkBeforeEncrypt(OWNER_A, CONV, transcript).test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertValue(KeysetTrustStore.CheckResult.UNVERIFIED);
        assertTrue(store.state(OWNER_A, CONV).blockingGet().isEmpty());

        store.markVerified(OWNER_A, CONV, transcript).test()
                .awaitDone(5, TimeUnit.SECONDS).assertComplete();

        store.checkBeforeEncrypt(OWNER_A, CONV, transcript).test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertValue(KeysetTrustStore.CheckResult.VERIFIED_MATCH);
        KeysetTrustStore.Record record = store.state(OWNER_A, CONV).blockingGet()
                .orElseThrow(AssertionError::new);
        assertEquals(KeysetTrustStore.TrustState.VERIFIED, record.state());
        assertEquals(hex(KeysetCheck.digest(transcript)), record.verifiedDigestHex());
        assertArrayEquals(transcript, record.verifiedTranscript());
        assertNull(record.changedDigestHex());
    }

    @Test
    public void addedDeviceFlipsChangedAcrossNewStoreInstance() throws Exception {
        byte[] verified = baseTranscript();
        byte[] grown = addedDeviceTranscript();
        store.markVerified(OWNER_A, CONV, verified).blockingAwait();

        store.checkBeforeEncrypt(OWNER_A, CONV, grown).test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertValue(KeysetTrustStore.CheckResult.CHANGED);

        // A fresh store over the same table is the restart boundary.
        KeysetTrustStore restarted = new KeysetTrustStore(dao);
        restarted.checkBeforeEncrypt(OWNER_A, CONV, grown).test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertValue(KeysetTrustStore.CheckResult.CHANGED);
        KeysetTrustStore.Record record = restarted.state(OWNER_A, CONV).blockingGet()
                .orElseThrow(AssertionError::new);
        assertEquals(KeysetTrustStore.TrustState.CHANGED, record.state());
        assertEquals(hex(KeysetCheck.digest(verified)), record.verifiedDigestHex());
        assertEquals(hex(KeysetCheck.digest(grown)), record.changedDigestHex());
        assertArrayEquals(verified, record.verifiedTranscript());
    }

    @Test
    public void changedStaysWhenTheOldRosterReturns() throws Exception {
        byte[] verified = baseTranscript();
        byte[] grown = addedDeviceTranscript();
        store.markVerified(OWNER_A, CONV, verified).blockingAwait();
        store.checkBeforeEncrypt(OWNER_A, CONV, grown).blockingGet();

        store.checkBeforeEncrypt(OWNER_A, CONV, verified).test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertValue(KeysetTrustStore.CheckResult.CHANGED);
    }

    @Test
    public void markVerifiedClearsChanged() throws Exception {
        byte[] verified = baseTranscript();
        byte[] grown = addedDeviceTranscript();
        store.markVerified(OWNER_A, CONV, verified).blockingAwait();
        store.checkBeforeEncrypt(OWNER_A, CONV, grown).blockingGet();

        store.markVerified(OWNER_A, CONV, grown).blockingAwait();

        store.checkBeforeEncrypt(OWNER_A, CONV, grown).test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertValue(KeysetTrustStore.CheckResult.VERIFIED_MATCH);
        KeysetTrustStore.Record record = store.state(OWNER_A, CONV).blockingGet()
                .orElseThrow(AssertionError::new);
        assertEquals(KeysetTrustStore.TrustState.VERIFIED, record.state());
        assertNull(record.changedDigestHex());
    }

    @Test
    public void ownersAreIsolated() throws Exception {
        byte[] transcript = baseTranscript();
        store.markVerified(OWNER_A, CONV, transcript).blockingAwait();

        store.checkBeforeEncrypt(OWNER_B, CONV, transcript).test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertValue(KeysetTrustStore.CheckResult.UNVERIFIED);
        Optional<KeysetTrustStore.Record> other =
                store.state(OWNER_B, CONV).blockingGet();
        assertTrue(other.isEmpty());
        assertEquals(KeysetTrustStore.TrustState.VERIFIED,
                store.state(OWNER_A, CONV).blockingGet()
                        .orElseThrow(AssertionError::new).state());
    }

    @Test
    public void clearForConversationAndOwnerFallBackToUnverified() throws Exception {
        byte[] transcript = baseTranscript();
        String otherConversation = "99999999-9999-9999-9999-999999999999";
        store.markVerified(OWNER_A, CONV, transcript).blockingAwait();
        store.markVerified(OWNER_A, otherConversation, transcript).blockingAwait();
        store.markVerified(OWNER_B, CONV, transcript).blockingAwait();

        store.clearForConversation(OWNER_A, CONV).blockingAwait();

        assertTrue(store.state(OWNER_A, CONV).blockingGet().isEmpty());
        assertTrue(store.state(OWNER_A, otherConversation).blockingGet().isPresent());
        assertTrue(store.state(OWNER_B, CONV).blockingGet().isPresent());

        store.clearForOwner(OWNER_A).blockingAwait();

        assertTrue(store.state(OWNER_A, otherConversation).blockingGet().isEmpty());
        assertTrue(store.state(OWNER_B, CONV).blockingGet().isPresent());
    }

    // ---- fixtures ----

    private static byte[] baseTranscript() throws KeysetCheck.InvalidKeysetException {
        return KeysetCheck.transcript(UUID.fromString(CONV), List.of(
                new KeysetCheck.UserRoster(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        List.of(device("44444444-4444-4444-4444-444444444444", 0x11))),
                new KeysetCheck.UserRoster(
                        UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        List.of(device("55555555-5555-5555-5555-555555555555", 0x22)))));
    }

    private static byte[] addedDeviceTranscript() throws KeysetCheck.InvalidKeysetException {
        return KeysetCheck.transcript(UUID.fromString(CONV), List.of(
                new KeysetCheck.UserRoster(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        List.of(device("44444444-4444-4444-4444-444444444444", 0x11))),
                new KeysetCheck.UserRoster(
                        UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        List.of(device("55555555-5555-5555-5555-555555555555", 0x22),
                                device("77777777-7777-7777-7777-777777777777", 0x77)))));
    }

    private static KeysetCheck.Device device(String id, int keySeed) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) keySeed);
        return new KeysetCheck.Device(UUID.fromString(id), KeysetCheck.ALGORITHM_X25519, key);
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] digits = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = digits[b >>> 4];
            out[i * 2 + 1] = digits[b & 0x0F];
        }
        return new String(out);
    }
}
