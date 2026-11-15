package com.nexachat.core.crypto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical transcript, SHA-256 digest and {@code NC1} verification code that
 * commit to the exact two-user device keyset of an E2EE direct conversation.
 *
 * <p>Transcript layout: ASCII {@code "NexaChat/keyset-check/1"} + one NUL byte,
 * the conversation UUID (16 network-order bytes: most-significant long then
 * least-significant long, big-endian), then the two user records sorted by
 * UNSIGNED lexicographic UUID bytes. Each record is the user UUID, a U16-BE
 * device count, and the devices sorted by unsigned UUID; each device is its
 * UUID, algorithm byte {@code 0x01} (X25519) and the raw 32-byte public key.
 * Names and timestamps never enter the transcript, and canonical sorting makes
 * the output independent of input ordering.
 *
 * <p>{@link #parseQrPayload} fails closed: wrong prefix, version, encoding or
 * digest all reject, and {@link #matches} only confirms a scanned payload
 * against the locally derived transcript.
 */
public final class KeysetCheck {

    /** The only device key algorithm the v1 transcript admits. */
    public static final int ALGORITHM_X25519 = 1;

    /**
     * Rejection for malformed rosters and untrusted QR payloads. Messages never
     * carry key material, ids or raw bytes — only the violated rule.
     */
    public static final class InvalidKeysetException extends Exception {
        public InvalidKeysetException(@NonNull String message) {
            super(message);
        }
    }

    /**
     * One device's identity key row, independent of network DTOs. Key bytes are
     * copied defensively; nulls are tolerated only so {@link #transcript} can
     * reject them with the typed exception.
     */
    public record Device(@NonNull UUID id, int algorithm, @NonNull byte[] publicKey) {
        public Device {
            if (publicKey != null) publicKey = publicKey.clone();
        }

        @Override
        public byte[] publicKey() {
            return publicKey == null ? null : publicKey.clone();
        }
    }

    /** One user's device roster; the device list is copied defensively. */
    public record UserRoster(@NonNull UUID userId, @NonNull List<Device> devices) {
        public UserRoster {
            if (devices != null) {
                devices = Collections.unmodifiableList(new ArrayList<>(devices));
            }
        }
    }

    /**
     * Reserved for the in-person QR verification feature: package-private so
     * nothing outside this crypto package can depend on the format before the
     * feature ships.
     */
    record ParsedPayload(@NonNull byte[] transcript, @NonNull byte[] digest) {
        public ParsedPayload {
            transcript = transcript.clone();
            digest = digest.clone();
        }

        @Override
        public byte[] transcript() {
            return transcript.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }
    }

    private static final int UUID_BYTES = 16;
    private static final int PUBLIC_KEY_BYTES = 32;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_DEVICES_PER_USER = 0xFFFF;
    private static final byte[] HEADER = header();
    private static final String QR_PREFIX = "nexachat-keyset:1:";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final Comparator<byte[]> UNSIGNED_LEX = KeysetCheck::compareUnsigned;

    private KeysetCheck() {
    }

    /**
     * Builds the canonical transcript for {@code rosters}, which must contain
     * exactly two distinct users each carrying at least one X25519 device key.
     */
    @NonNull
    public static byte[] transcript(@NonNull UUID conversationId,
            @NonNull List<UserRoster> rosters) throws InvalidKeysetException {
        validate(conversationId, rosters);
        List<UserRoster> users = new ArrayList<>(rosters);
        users.sort(Comparator.comparing(u -> uuidBytes(u.userId()), UNSIGNED_LEX));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, HEADER);
        write(out, uuidBytes(conversationId));
        for (UserRoster user : users) {
            List<Device> devices = new ArrayList<>(user.devices());
            devices.sort(Comparator.comparing(d -> uuidBytes(d.id()), UNSIGNED_LEX));
            write(out, uuidBytes(user.userId()));
            out.write((devices.size() >>> 8) & 0xFF);
            out.write(devices.size() & 0xFF);
            for (Device device : devices) {
                write(out, uuidBytes(device.id()));
                out.write(ALGORITHM_X25519);
                write(out, device.publicKey());
            }
        }
        return out.toByteArray();
    }

    /** SHA-256 over the canonical transcript. */
    @NonNull
    public static byte[] digest(@NonNull byte[] transcript) {
        return sha256(transcript);
    }

    /**
     * {@code "NC1"} plus the 64 uppercase hex digits of {@code digest}, grouped
     * in fours and separated by single spaces — 16 groups total.
     */
    @NonNull
    public static String displayCode(@NonNull byte[] digest) {
        if (digest.length != DIGEST_BYTES) {
            throw new IllegalArgumentException("digest must be 32 bytes");
        }
        StringBuilder out = new StringBuilder("NC1 ");
        for (int i = 0; i < digest.length; i++) {
            if (i > 0 && i % 2 == 0) out.append(' ');
            int b = digest[i] & 0xFF;
            out.append(HEX[b >>> 4]).append(HEX[b & 0x0F]);
        }
        return out.toString();
    }

    /**
     * {@code "nexachat-keyset:1:"} + base64url (no padding) of
     * transcript||digest. Reserved for the QR feature; package-private until
     * it ships.
     */
    @NonNull
    static String qrPayload(@NonNull byte[] transcript, @NonNull byte[] digest) {
        byte[] blob = new byte[transcript.length + digest.length];
        System.arraycopy(transcript, 0, blob, 0, transcript.length);
        System.arraycopy(digest, 0, blob, transcript.length, digest.length);
        return QR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(blob);
    }

    /**
     * Parses a scanned {@link #qrPayload} string. Requires the exact
     * {@code nexachat-keyset:1:} prefix, the unpadded base64url alphabet, the
     * transcript header followed by at least a digest, and a recomputed
     * SHA-256 equal to the claimed digest (compared in constant time).
     * Reserved for the QR feature; package-private until it ships.
     */
    @NonNull
    static ParsedPayload parseQrPayload(@Nullable String payload)
            throws InvalidKeysetException {
        if (payload == null || !payload.startsWith(QR_PREFIX)) {
            throw new InvalidKeysetException("unrecognized keyset payload");
        }
        String encoded = payload.substring(QR_PREFIX.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                throw new InvalidKeysetException("keyset payload is not base64url");
            }
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new InvalidKeysetException("keyset payload is not base64url");
        }
        if (decoded.length < HEADER.length + DIGEST_BYTES || !startsWith(decoded, HEADER)) {
            throw new InvalidKeysetException("keyset payload is truncated");
        }
        int split = decoded.length - DIGEST_BYTES;
        byte[] transcript = Arrays.copyOfRange(decoded, 0, split);
        byte[] claimedDigest = Arrays.copyOfRange(decoded, split, decoded.length);
        if (!MessageDigest.isEqual(sha256(transcript), claimedDigest)) {
            throw new InvalidKeysetException("keyset payload digest mismatch");
        }
        return new ParsedPayload(transcript, claimedDigest);
    }

    /**
     * True only when {@code scannedPayload} parses, its digest verifies and its
     * transcript equals {@code localTranscript} byte-for-byte. Reserved for
     * the QR feature; package-private until it ships.
     */
    static boolean matches(@Nullable byte[] localTranscript,
            @Nullable String scannedPayload) {
        if (localTranscript == null) {
            return false;
        }
        try {
            return Arrays.equals(localTranscript, parseQrPayload(scannedPayload).transcript());
        } catch (InvalidKeysetException e) {
            return false;
        }
    }

    private static void validate(@Nullable UUID conversationId,
            @Nullable List<UserRoster> rosters) throws InvalidKeysetException {
        if (conversationId == null) {
            throw new InvalidKeysetException("conversation id must not be null");
        }
        if (rosters == null || rosters.size() != 2) {
            throw new InvalidKeysetException("keyset needs exactly 2 user rosters");
        }
        Set<UUID> seenUsers = new HashSet<>();
        Set<UUID> seenDevices = new HashSet<>();
        for (UserRoster roster : rosters) {
            if (roster == null || roster.userId() == null || roster.devices() == null) {
                throw new InvalidKeysetException("user roster has null fields");
            }
            if (!seenUsers.add(roster.userId())) {
                throw new InvalidKeysetException("duplicate user id in keyset");
            }
            if (roster.devices().isEmpty()) {
                throw new InvalidKeysetException("user roster has no devices");
            }
            if (roster.devices().size() > MAX_DEVICES_PER_USER) {
                throw new InvalidKeysetException("user roster exceeds 65535 devices");
            }
            for (Device device : roster.devices()) {
                if (device == null || device.id() == null || device.publicKey() == null) {
                    throw new InvalidKeysetException("device entry has null fields");
                }
                if (!seenDevices.add(device.id())) {
                    throw new InvalidKeysetException("duplicate device id in keyset");
                }
                if (device.algorithm() != ALGORITHM_X25519) {
                    throw new InvalidKeysetException("unsupported device key algorithm");
                }
                if (device.publicKey().length != PUBLIC_KEY_BYTES) {
                    throw new InvalidKeysetException("device public key must be 32 bytes");
                }
            }
        }
    }

    /** 16 network-order bytes: MSB long then LSB long, big-endian. */
    private static byte[] uuidBytes(@NonNull UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(UUID_BYTES);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return buffer.array();
    }

    /** Unsigned lexicographic byte order; never signed (UUID.compareTo is signed). */
    private static int compareUnsigned(byte[] a, byte[] b) {
        int common = Math.min(a.length, b.length);
        for (int i = 0; i < common; i++) {
            int cmp = Byte.compareUnsigned(a[i], b[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.length, b.length);
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] header() {
        byte[] magic = "NexaChat/keyset-check/1".getBytes(StandardCharsets.US_ASCII);
        return Arrays.copyOf(magic, magic.length + 1);
    }
}
