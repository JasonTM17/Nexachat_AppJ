package com.nexachat.core.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.crypto.KeysetCheck.Device;
import com.nexachat.core.crypto.KeysetCheck.InvalidKeysetException;
import com.nexachat.core.crypto.KeysetCheck.ParsedPayload;
import com.nexachat.core.crypto.KeysetCheck.UserRoster;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

/** Canonical layout, ordering, rejection and QR round-trip rules for the NC1 keyset code. */
public final class KeysetCheckTest {

    private static final UUID CONVERSATION =
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID ALICE =
            UUID.fromString("0e7c9d12-3456-7890-abcd-ef1234567890");
    private static final UUID BOB =
            UUID.fromString("8f3a2b1c-0000-1111-2222-333344445555");
    private static final UUID ALICE_PHONE =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ALICE_TAB =
            UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID BOB_PHONE =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID PHANTOM =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static Device dev(UUID id, int keyStart) {
        return dev(id, keyStart, KeysetCheck.ALGORITHM_X25519);
    }

    private static Device dev(UUID id, int keyStart, int algorithm) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (keyStart + i);
        return new Device(id, algorithm, key);
    }

    /** The fixed golden-vector roster: Alice two devices, Bob one. */
    private static List<UserRoster> rosters() {
        return List.of(
                new UserRoster(ALICE, List.of(dev(ALICE_PHONE, 0x00), dev(ALICE_TAB, 0x20))),
                new UserRoster(BOB, List.of(dev(BOB_PHONE, 0x40))));
    }

    private static byte[] transcript() throws InvalidKeysetException {
        return KeysetCheck.transcript(CONVERSATION, rosters());
    }

    @Test
    public void fixedVectorLocksTranscriptDigestAndCode() throws Exception {
        byte[] transcript = transcript();
        assertEquals(223, transcript.length);
        assertEquals(EXPECTED_TRANSCRIPT_HEX, E2eeCrypto.hex(transcript));
        byte[] digest = KeysetCheck.digest(transcript);
        assertEquals(EXPECTED_DIGEST_HEX, E2eeCrypto.hex(digest));
        assertEquals(EXPECTED_NC1, KeysetCheck.displayCode(digest));
    }

    @Test
    public void userAndDeviceInputOrderNeverChangesTheTranscript() throws Exception {
        byte[] canonical = transcript();
        List<UserRoster> reversed = List.of(
                new UserRoster(BOB, List.of(dev(BOB_PHONE, 0x40))),
                new UserRoster(ALICE, List.of(dev(ALICE_TAB, 0x20), dev(ALICE_PHONE, 0x00))));
        assertArrayEquals(canonical, KeysetCheck.transcript(CONVERSATION, reversed));
    }

    @Test
    public void anyKeysetChangeChangesTheDigest() throws Exception {
        byte[] base = KeysetCheck.digest(transcript());

        // One flipped key byte.
        byte[] tamperedKey = dev(ALICE_PHONE, 0x00).publicKey();
        tamperedKey[7] ^= 0x01;
        assertNotEquals(E2eeCrypto.hex(base), E2eeCrypto.hex(KeysetCheck.digest(
                KeysetCheck.transcript(CONVERSATION, List.of(
                        new UserRoster(ALICE, List.of(
                                new Device(ALICE_PHONE, KeysetCheck.ALGORITHM_X25519, tamperedKey),
                                dev(ALICE_TAB, 0x20))),
                        new UserRoster(BOB, List.of(dev(BOB_PHONE, 0x40))))))));

        // A phantom extra device served to both sides must flip the code.
        byte[] withPhantom = KeysetCheck.digest(KeysetCheck.transcript(CONVERSATION, List.of(
                new UserRoster(ALICE, List.of(dev(ALICE_PHONE, 0x00), dev(ALICE_TAB, 0x20))),
                new UserRoster(BOB, List.of(dev(BOB_PHONE, 0x40), dev(PHANTOM, 0x60))))));
        assertFalse(Arrays.equals(base, withPhantom));

        // A removed device flips the code.
        byte[] deviceRemoved = KeysetCheck.digest(KeysetCheck.transcript(CONVERSATION, List.of(
                new UserRoster(ALICE, List.of(dev(ALICE_PHONE, 0x00))),
                new UserRoster(BOB, List.of(dev(BOB_PHONE, 0x40))))));
        assertFalse(Arrays.equals(base, deviceRemoved));

        // The same key material swapped between two device ids flips the code.
        byte[] keysSwapped = KeysetCheck.digest(KeysetCheck.transcript(CONVERSATION, List.of(
                new UserRoster(ALICE, List.of(dev(ALICE_PHONE, 0x20), dev(ALICE_TAB, 0x00))),
                new UserRoster(BOB, List.of(dev(BOB_PHONE, 0x40))))));
        assertFalse(Arrays.equals(base, keysSwapped));
    }

    @Test
    public void userOrderingIsUnsignedLexicographic() throws Exception {
        // UUID.compareTo is signed and would sort the 0x80-leading id first.
        UUID low = UUID.fromString("7f000000-0000-0000-0000-000000000000");
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        List<UserRoster> rosters = List.of(
                new UserRoster(high, List.of(dev(BOB_PHONE, 0x40))),
                new UserRoster(low, List.of(
                        dev(UUID.fromString("ff000000-0000-0000-0000-000000000000"), 0x00),
                        dev(UUID.fromString("01000000-0000-0000-0000-000000000000"), 0x20))));
        byte[] transcript = KeysetCheck.transcript(CONVERSATION, rosters);
        // Header is 24 bytes, conversation 16: the first user record starts at 40.
        assertEquals(0x7F, transcript[40] & 0xFF);
        // That user's first device (2-byte count after the 16-byte id) is the
        // 0x01-leading id, not the 0xFF-leading one.
        assertEquals(0x00, transcript[56] & 0xFF);
        assertEquals(0x02, transcript[57] & 0xFF);
        assertEquals(0x01, transcript[58] & 0xFF);
        // Second user record starts at 40 + 16 + 2 + 2*(16+1+32) = 156.
        assertEquals(0x80, transcript[156] & 0xFF);
    }

    @Test
    public void rejectsUserCountsOtherThanTwo() {
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(rosters().get(0))));
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(
                        rosters().get(0), rosters().get(1),
                        new UserRoster(PHANTOM, List.of(dev(PHANTOM, 0x60))))));
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of()));
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, null));
    }

    @Test
    public void rejectsTheSameUserTwice() {
        UserRoster alice = rosters().get(0);
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(alice, alice)));
        // Two different roster objects carrying the same user id also reject.
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(
                        new UserRoster(ALICE, List.of(dev(ALICE_PHONE, 0x00))),
                        new UserRoster(ALICE, List.of(dev(ALICE_TAB, 0x20))))));
    }

    @Test
    public void rejectsEmptyDeviceLists() {
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(
                        new UserRoster(ALICE, List.of()),
                        rosters().get(1))));
    }

    @Test
    public void rejectsRostersOverTheDeviceLimit() {
        List<Device> tooMany = new ArrayList<>(0x10000);
        for (int i = 0; i <= 0xFFFF; i++) {
            tooMany.add(dev(UUID.randomUUID(), 0x00));
        }
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(
                        new UserRoster(ALICE, tooMany), rosters().get(1))));
    }

    @Test
    public void rejectsDuplicateDeviceIds() {
        // Within one user's roster.
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(
                        new UserRoster(ALICE, List.of(dev(ALICE_PHONE, 0x00), dev(ALICE_PHONE, 0x20))),
                        rosters().get(1))));
        // The same device id appearing under the other user is still a duplicate.
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(
                        new UserRoster(ALICE, List.of(dev(ALICE_PHONE, 0x00))),
                        new UserRoster(BOB, List.of(dev(ALICE_PHONE, 0x40), dev(BOB_PHONE, 0x60))))));
    }

    @Test
    public void rejectsNonX25519Algorithms() {
        for (int algorithm : new int[] {0, 2, -1, 255}) {
            assertThrows(InvalidKeysetException.class,
                    () -> KeysetCheck.transcript(CONVERSATION, List.of(
                            new UserRoster(ALICE, List.of(dev(ALICE_PHONE, 0x00, algorithm))),
                            rosters().get(1))));
        }
    }

    @Test
    public void rejectsKeysThatAreNot32Bytes() {
        for (int length : new int[] {0, 31, 33, 64}) {
            assertThrows(InvalidKeysetException.class,
                    () -> KeysetCheck.transcript(CONVERSATION, List.of(
                            new UserRoster(ALICE, List.of(
                                    new Device(ALICE_PHONE, KeysetCheck.ALGORITHM_X25519,
                                            new byte[length]))),
                            rosters().get(1))));
        }
    }

    @Test
    public void rejectsNullFields() {
        UserRoster valid = rosters().get(0);
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(null, rosters()));
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, Arrays.asList(valid, null)));
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(
                        new UserRoster(null, List.of(dev(ALICE_PHONE, 0x00))), rosters().get(1))));
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(
                        new UserRoster(ALICE, null), rosters().get(1))));
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(
                        new UserRoster(ALICE, Arrays.asList(dev(ALICE_PHONE, 0x00), null)),
                        rosters().get(1))));
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(
                        new UserRoster(ALICE, List.of(
                                new Device(null, KeysetCheck.ALGORITHM_X25519, new byte[32]))),
                        rosters().get(1))));
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.transcript(CONVERSATION, List.of(
                        new UserRoster(ALICE, List.of(
                                new Device(ALICE_PHONE, KeysetCheck.ALGORITHM_X25519, null))),
                        rosters().get(1))));
    }

    @Test
    public void qrPayloadRoundTripsAndMatches() throws Exception {
        byte[] transcript = transcript();
        byte[] digest = KeysetCheck.digest(transcript);
        String payload = KeysetCheck.qrPayload(transcript, digest);
        assertTrue(payload.startsWith("nexachat-keyset:1:"));
        ParsedPayload parsed = KeysetCheck.parseQrPayload(payload);
        assertArrayEquals(transcript, parsed.transcript());
        assertArrayEquals(digest, parsed.digest());
        assertTrue(KeysetCheck.matches(transcript, payload));
    }

    @Test
    public void qrPayloadRejectsTamperedTranscript() throws Exception {
        byte[] transcript = transcript();
        String payload = KeysetCheck.qrPayload(transcript, KeysetCheck.digest(transcript));
        byte[] blob = Base64.getUrlDecoder().decode(payload.substring("nexachat-keyset:1:".length()));
        blob[30] ^= 0x01;
        String tampered = "nexachat-keyset:1:"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(blob);
        assertThrows(InvalidKeysetException.class, () -> KeysetCheck.parseQrPayload(tampered));
        assertFalse(KeysetCheck.matches(transcript, tampered));
    }

    @Test
    public void qrPayloadRejectsTamperedDigest() throws Exception {
        byte[] transcript = transcript();
        String payload = KeysetCheck.qrPayload(transcript, KeysetCheck.digest(transcript));
        byte[] blob = Base64.getUrlDecoder().decode(payload.substring("nexachat-keyset:1:".length()));
        blob[blob.length - 1] ^= 0x01;
        String tampered = "nexachat-keyset:1:"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(blob);
        assertThrows(InvalidKeysetException.class, () -> KeysetCheck.parseQrPayload(tampered));
        assertFalse(KeysetCheck.matches(transcript, tampered));
    }

    @Test
    public void qrPayloadRejectsOtherVersionsAndPrefixes() throws Exception {
        byte[] transcript = transcript();
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(concat(transcript, KeysetCheck.digest(transcript)));
        for (String bad : new String[] {
                "nexachat-keyset:2:" + encoded,
                "nexachat-keyset:0:" + encoded,
                "nexachat-keyset:" + encoded,
                "NexaChat-keyset:1:" + encoded,
                "other:" + encoded}) {
            assertThrows(InvalidKeysetException.class, () -> KeysetCheck.parseQrPayload(bad));
            assertFalse(KeysetCheck.matches(transcript, bad));
        }
    }

    @Test
    public void qrPayloadRejectsNonUrlAlphabetAndPadding() throws Exception {
        byte[] transcript = transcript();
        String payload = KeysetCheck.qrPayload(transcript, KeysetCheck.digest(transcript));
        // Any '=' padding, even appended to a valid body, rejects.
        assertThrows(InvalidKeysetException.class,
                () -> KeysetCheck.parseQrPayload(payload + "="));
        // Standard base64 alphabet characters are not the URL-safe alphabet.
        for (String tampered : new String[] {
                payload.substring(0, 30) + "+" + payload.substring(31),
                payload.substring(0, 30) + "/" + payload.substring(31),
                payload + " ",
                payload.substring(0, payload.length() - 1) + "*"}) {
            assertThrows(InvalidKeysetException.class, () -> KeysetCheck.parseQrPayload(tampered));
        }
    }

    @Test
    public void qrPayloadRejectsEmptyTruncatedAndMissingHeader() throws Exception {
        for (String bad : new String[] {"", "nexachat-keyset:1:", "nexachat-keyset:1:!!!",
                "not a payload"}) {
            assertThrows(InvalidKeysetException.class, () -> KeysetCheck.parseQrPayload(bad));
        }
        // Header followed by fewer than 32 bytes has no room for a digest.
        byte[] header = "NexaChat/keyset-check/1".getBytes(StandardCharsets.US_ASCII);
        byte[] shortBlob = new byte[header.length + 1 + 31];
        System.arraycopy(header, 0, shortBlob, 0, header.length);
        String truncated = "nexachat-keyset:1:"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(shortBlob);
        assertThrows(InvalidKeysetException.class, () -> KeysetCheck.parseQrPayload(truncated));
        // A self-consistent digest over a blob without the transcript header rejects.
        byte[] noHeader = new byte[72];
        for (int i = 0; i < 40; i++) noHeader[i] = (byte) i;
        byte[] digest = KeysetCheck.digest(Arrays.copyOf(noHeader, 40));
        System.arraycopy(digest, 0, noHeader, 40, digest.length);
        String missingHeader = "nexachat-keyset:1:"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(noHeader);
        assertThrows(InvalidKeysetException.class, () -> KeysetCheck.parseQrPayload(missingHeader));
    }

    @Test
    public void matchesRequiresTheIdenticalTranscript() throws Exception {
        byte[] transcript = transcript();
        String payload = KeysetCheck.qrPayload(transcript, KeysetCheck.digest(transcript));
        // A different conversation yields a different transcript: parse succeeds,
        // match fails.
        byte[] other = KeysetCheck.transcript(
                UUID.fromString("99999999-9999-9999-9999-999999999999"), rosters());
        String otherPayload = KeysetCheck.qrPayload(other, KeysetCheck.digest(other));
        assertTrue(KeysetCheck.matches(transcript, payload));
        assertFalse(KeysetCheck.matches(transcript, otherPayload));
        assertFalse(KeysetCheck.matches(null, payload));
        assertFalse(KeysetCheck.matches(transcript, "garbage"));
    }

    @Test
    public void displayCodeIsNc1PlusSixteenUppercaseHexGroups() throws Exception {
        String code = KeysetCheck.displayCode(KeysetCheck.digest(transcript()));
        assertEquals(4 + 64 + 15, code.length());
        assertTrue(code.startsWith("NC1 "));
        String[] groups = code.substring(4).split(" ");
        assertEquals(16, groups.length);
        for (String group : groups) {
            assertEquals(4, group.length());
            assertTrue(group.chars().allMatch(
                    c -> (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')));
        }
    }

    @Test
    public void displayCodeRejectsNonDigestLength() {
        assertThrows(IllegalArgumentException.class,
                () -> KeysetCheck.displayCode(new byte[16]));
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    // Independently computed with hashlib/uuid over the spec layout; see report.
    private static final String EXPECTED_TRANSCRIPT_HEX =
            "4e657861436861742f6b65797365742d636865636b2f3100123e4567e89b12d3a456426614174000"
            + "0e7c9d1234567890abcdef1234567890"
            + "0002"
            + "22222222222222222222222222222222"
            + "01" + "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
            + "99999999999999999999999999999999"
            + "01" + "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
            + "8f3a2b1c000011112222333344445555"
            + "0001"
            + "55555555555555555555555555555555"
            + "01" + "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f";
    private static final String EXPECTED_DIGEST_HEX =
            "4205270df1106d0cd904e6751dd423b77f538291d6e098484581504605399d4f";
    private static final String EXPECTED_NC1 =
            "NC1 4205 270D F110 6D0C D904 E675 1DD4 23B7 7F53 8291 D6E0 9848 4581 5046 0539 9D4F";
}
