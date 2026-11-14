package com.nexachat.core.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.crypto.E2eeCrypto.DevicePublicKey;
import com.nexachat.core.crypto.E2eeCrypto.EncryptedMessage;
import com.nexachat.core.crypto.E2eeCrypto.Envelope;
import com.nexachat.core.crypto.E2eeCrypto.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** Round-trip, tamper and fixed-vector coverage for the E2EE v1 wire format. */
public final class E2eeCryptoTest {

    private static final String CONVERSATION = "11111111-1111-1111-1111-111111111111";
    private static final String SENDER = "22222222-2222-2222-2222-222222222222";
    private static final String CLIENT_MESSAGE_ID = "33333333-3333-3333-3333-333333333333";

    private final E2eeCrypto crypto = new E2eeCrypto();

    private static KeyPair pair(int seed) throws Exception {
        byte[] priv = new byte[32];
        priv[0] = (byte) seed;
        priv[1] = (byte) (seed + 1);
        return new KeyPair(priv, E2eeCrypto.publicFromPrivate(priv));
    }

    private static DevicePublicKey device(String id, KeyPair pair) {
        return new DevicePublicKey(id, pair.publicKey());
    }

    @Test
    public void roundTripForOneToThreeDevices() throws Exception {
        KeyPair alice = pair(1);
        KeyPair bob1 = pair(2);
        KeyPair bob2 = pair(3);
        List<DevicePublicKey> recipients = List.of(
                device("alice-phone", alice), device("bob-phone", bob1), device("bob-tab", bob2));
        EncryptedMessage out = crypto.encrypt("xin chào 🔐", CONVERSATION, SENDER,
                CLIENT_MESSAGE_ID, recipients);
        assertTrue(out.content().startsWith("e2ee1:"));
        assertEquals(3, out.envelopes().size());
        assertTrue(E2eeCrypto.isEncryptedWireContent(out.content()));
        for (int i = 0; i < recipients.size(); i++) {
            KeyPair key = List.of(alice, bob1, bob2).get(i);
            Optional<String> back = crypto.decrypt(out.content(), out.envelopes(),
                    recipients.get(i).deviceId(), key.privateKey(),
                    CONVERSATION, SENDER, CLIENT_MESSAGE_ID);
            assertTrue(back.isPresent());
            assertEquals("xin chào 🔐", back.get());
        }
        // A single-device encrypt still unwraps for that device only.
        EncryptedMessage single = crypto.encrypt("hi", CONVERSATION, SENDER,
                CLIENT_MESSAGE_ID, List.of(device("alice-phone", alice)));
        assertEquals("hi", crypto.decrypt(single.content(), single.envelopes(), "alice-phone",
                alice.privateKey(), CONVERSATION, SENDER, CLIENT_MESSAGE_ID).orElseThrow());
    }

    @Test
    public void wrongDeviceIdOrMissingEnvelopeReturnsEmpty() throws Exception {
        KeyPair alice = pair(1);
        KeyPair bob = pair(2);
        EncryptedMessage out = crypto.encrypt("secret", CONVERSATION, SENDER, CLIENT_MESSAGE_ID,
                List.of(device("alice-phone", alice)));
        assertTrue(crypto.decrypt(out.content(), out.envelopes(), "bob-phone", bob.privateKey(),
                CONVERSATION, SENDER, CLIENT_MESSAGE_ID).isEmpty());
        // Bob's device id is not among the envelopes even though his key could unwrap.
        EncryptedMessage forBob = crypto.encrypt("secret", CONVERSATION, SENDER, CLIENT_MESSAGE_ID,
                List.of(device("bob-phone", bob)));
        assertTrue(crypto.decrypt(forBob.content(), forBob.envelopes(), "alice-phone",
                alice.privateKey(), CONVERSATION, SENDER, CLIENT_MESSAGE_ID).isEmpty());
        assertTrue(crypto.decrypt(out.content(), List.of(), "alice-phone", alice.privateKey(),
                CONVERSATION, SENDER, CLIENT_MESSAGE_ID).isEmpty());
        assertTrue(crypto.decrypt(out.content(), out.envelopes(), "alice-phone", null,
                CONVERSATION, SENDER, CLIENT_MESSAGE_ID).isEmpty());
    }

    @Test
    public void tamperedCiphertextNonceOrEnvelopeReturnsEmpty() throws Exception {
        KeyPair alice = pair(1);
        EncryptedMessage out = crypto.encrypt("hello", CONVERSATION, SENDER, CLIENT_MESSAGE_ID,
                List.of(device("alice-phone", alice)));

        byte[] wire = Base64.getUrlDecoder().decode(out.content().substring(6));
        // Flip a byte in the ephemeral key, the nonce and the ciphertext tail.
        for (int index : new int[] {0, 32, wire.length - 1}) {
            byte[] tampered = wire.clone();
            tampered[index] ^= 0x01;
            String content = "e2ee1:" + E2eeCrypto.b64url(tampered);
            assertTrue("tamper at " + index,
                    crypto.decrypt(content, out.envelopes(), "alice-phone", alice.privateKey(),
                            CONVERSATION, SENDER, CLIENT_MESSAGE_ID).isEmpty());
        }
        // Tamper inside the wrapped key envelope.
        Envelope env = out.envelopes().get(0);
        byte[] wrapped = Base64.getUrlDecoder().decode(env.wrappedKey());
        wrapped[wrapped.length - 1] ^= 0x01;
        List<Envelope> badEnvelopes = List.of(
                new Envelope(env.deviceId(), E2eeCrypto.b64url(wrapped)));
        assertTrue(crypto.decrypt(out.content(), badEnvelopes, "alice-phone", alice.privateKey(),
                CONVERSATION, SENDER, CLIENT_MESSAGE_ID).isEmpty());
    }

    @Test
    public void associatedDataMismatchReturnsEmpty() throws Exception {
        KeyPair alice = pair(1);
        EncryptedMessage out = crypto.encrypt("hello", CONVERSATION, SENDER, CLIENT_MESSAGE_ID,
                List.of(device("alice-phone", alice)));
        for (String[] ids : new String[][] {
                {"99999999-9999-9999-9999-999999999999", SENDER, CLIENT_MESSAGE_ID},
                {CONVERSATION, "99999999-9999-9999-9999-999999999999", CLIENT_MESSAGE_ID},
                {CONVERSATION, SENDER, "99999999-9999-9999-9999-999999999999"}}) {
            assertTrue(crypto.decrypt(out.content(), out.envelopes(), "alice-phone",
                    alice.privateKey(), ids[0], ids[1], ids[2]).isEmpty());
        }
    }

    @Test
    public void nonE2eeContentReturnsEmpty() throws Exception {
        KeyPair alice = pair(1);
        assertTrue(crypto.decrypt("plain text", List.of(), "alice-phone", alice.privateKey(),
                CONVERSATION, SENDER, CLIENT_MESSAGE_ID).isEmpty());
        assertTrue(crypto.decrypt("e2ee1:not-base64!!!", List.of(), "alice-phone",
                alice.privateKey(), CONVERSATION, SENDER, CLIENT_MESSAGE_ID).isEmpty());
        assertTrue(crypto.decrypt(null, List.of(), "alice-phone", alice.privateKey(),
                CONVERSATION, SENDER, CLIENT_MESSAGE_ID).isEmpty());
    }

    /**
     * Deterministic RNG: byte i of every request is {@code (counter + i)} so the
     * whole wire format is reproducible for the fixed vector below.
     */
    private static final class SequentialRandom extends SecureRandom {
        private int counter;

        SequentialRandom(int start) {
            this.counter = start;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (counter + i);
            }
            counter++;
        }
    }

    @Test
    public void decryptIgnoresNullEnvelopeEntries() throws Exception {
        KeyPair mine = pair(9);
        EncryptedMessage out = crypto.encrypt("boundary", CONVERSATION, SENDER,
                CLIENT_MESSAGE_ID, List.of(device("dev-9", mine)));

        // An untrusted payload may legally contain null elements; the mapper
        // must degrade to the placeholder path instead of crashing.
        List<Envelope> onlyNull = new ArrayList<>();
        onlyNull.add(null);
        assertTrue(crypto.decrypt(out.content(), onlyNull, "dev-9",
                mine.privateKey(), CONVERSATION, SENDER, CLIENT_MESSAGE_ID).isEmpty());

        List<Envelope> mixed = new ArrayList<>();
        mixed.add(null);
        mixed.add(out.envelopes().get(0));
        Optional<String> plain = crypto.decrypt(out.content(), mixed, "dev-9",
                mine.privateKey(), CONVERSATION, SENDER, CLIENT_MESSAGE_ID);
        assertTrue(plain.isPresent());
        assertEquals("boundary", plain.get());
    }

    @Test
    public void fixedVectorLocksTheWireFormat() throws Exception {
        // Sequential bytes 0..43 fill contentKey(32) then nonce(12); ephemeral
        // private key is pinned separately.
        E2eeCrypto pinned = new E2eeCrypto(new SequentialRandom(0));
        byte[] ephemeralPrivate = new byte[32];
        Arrays.fill(ephemeralPrivate, (byte) 0x55);
        byte[] recipientPublic = E2eeCrypto.publicFromPrivate(filled(0x33));
        EncryptedMessage out = pinned.encryptWithEphemeral("vector", CONVERSATION, SENDER,
                CLIENT_MESSAGE_ID, List.of(new DevicePublicKey("dev-1", recipientPublic)),
                ephemeralPrivate);

        byte[] wire = Base64.getUrlDecoder().decode(out.content().substring(6));
        assertEquals(32 + 12 + 6 + 16, wire.length);
        // Ephemeral public half of 0x55*32.
        byte[] expectedEphPub = E2eeCrypto.publicFromPrivate(ephemeralPrivate);
        assertTrue(Arrays.equals(expectedEphPub, Arrays.copyOfRange(wire, 0, 32)));
        // Nonce is the second SequentialRandom pull: bytes (1 + i).
        byte[] expectedNonce = new byte[12];
        for (int i = 0; i < 12; i++) expectedNonce[i] = (byte) (1 + i);
        assertTrue(Arrays.equals(expectedNonce, Arrays.copyOfRange(wire, 32, 44)));
        // Envelope payload is nonce(2 + i) || wrappedCt.
        byte[] envelope = Base64.getUrlDecoder().decode(out.envelopes().get(0).wrappedKey());
        byte[] expectedWrapNonce = new byte[12];
        for (int i = 0; i < 12; i++) expectedWrapNonce[i] = (byte) (2 + i);
        assertTrue(Arrays.equals(expectedWrapNonce, Arrays.copyOfRange(envelope, 0, 12)));
        // And the whole blob round-trips with the recipient's private key.
        assertEquals("vector", pinned.decrypt(out.content(), out.envelopes(), "dev-1",
                filled(0x33), CONVERSATION, SENDER, CLIENT_MESSAGE_ID).orElseThrow());
    }

    @Test
    public void wireFormatsMatchSpecLayout() throws Exception {
        KeyPair alice = pair(1);
        EncryptedMessage out = crypto.encrypt("x", CONVERSATION, SENDER, CLIENT_MESSAGE_ID,
                List.of(device("alice-phone", alice)));
        // e2ee1: prefix then b64url of 44-byte header + ct.
        assertTrue(out.content().startsWith("e2ee1:"));
        String b64 = out.content().substring(6);
        assertFalse(b64.contains("="));
        assertFalse(b64.contains("+") || b64.contains("/"));
        assertNotEquals(out.envelopes().get(0).wrappedKey(), "");
        assertFalse(out.envelopes().get(0).wrappedKey().contains("="));
    }

    private static byte[] filled(int value) {
        byte[] out = new byte[32];
        Arrays.fill(out, (byte) value);
        return out;
    }

    private static List<Envelope> envelopes(Envelope... items) {
        return new ArrayList<>(List.of(items));
    }
}
