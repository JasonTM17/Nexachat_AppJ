package com.nexachat.core.crypto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.crypto.tink.subtle.Hkdf;
import com.google.crypto.tink.subtle.X25519;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure-Java E2EE v1 primitives (no Android types). Wire formats:
 *
 * <ul>
 *   <li>content: {@code "e2ee1:" + base64url-nopad(ephPub32 || nonce12 || aesGcmCt)}
 *   <li>envelope: {@code base64url-nopad(nonce12 || wrappedCt)}
 *   <li>aad: {@code utf8("nexachat-e2ee-v1|" + conv + "|" + sender + "|" + clientMessageId)}
 *   <li>kek: {@code HKDF-SHA256(ikm=X25519(eph,dev), salt=utf8(clientMessageId),
 *       info=utf8("nexachat-e2ee-v1-kek|"+deviceId), 32)}
 * </ul>
 *
 * <p>{@link #decrypt} returns {@link Optional#empty()} on ANY failure — malformed
 * wire data, missing envelope, bad keys, tampered bytes — and never logs or throws
 * plaintext/key material to callers.
 */
public final class E2eeCrypto {

    public static final String CONTENT_PREFIX = "e2ee1:";
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    private static final String AAD_PREFIX = "nexachat-e2ee-v1|";
    private static final String KEK_INFO_PREFIX = "nexachat-e2ee-v1-kek|";
    private static final String HKDF_ALGORITHM = "HMACSHA256";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    /** A recipient's registered X25519 identity public key. */
    public record DevicePublicKey(@NonNull String deviceId, @NonNull byte[] publicKey) {
    }

    /** Per-device wrapped content key, serialized into {@code e2ee_envelopes}. */
    public record Envelope(@NonNull String deviceId, @NonNull String wrappedKey) {
    }

    /** Ciphertext wire content plus one envelope per recipient device. */
    public record EncryptedMessage(@NonNull String content, @NonNull List<Envelope> envelopes) {
    }

    /** X25519 device identity pair; {@code privateKey} must be sealed before persisting. */
    public record KeyPair(@NonNull byte[] privateKey, @NonNull byte[] publicKey) {
    }

    private final SecureRandom random;

    public E2eeCrypto() {
        this(new SecureRandom());
    }

    @VisibleForTesting
    E2eeCrypto(@NonNull SecureRandom random) {
        this.random = random;
    }

    /** Fresh X25519 identity pair for a device. */
    @NonNull
    public static KeyPair generateDeviceKeyPair() throws GeneralSecurityException {
        byte[] privateKey = X25519.generatePrivateKey();
        return new KeyPair(privateKey, X25519.publicFromPrivate(privateKey));
    }

    /** Derives the X25519 public half of a private identity key. */
    public static @NonNull byte[] publicFromPrivate(@NonNull byte[] privateKey)
            throws GeneralSecurityException {
        if (privateKey.length != KEY_BYTES) {
            throw new GeneralSecurityException("X25519 private key must be 32 bytes");
        }
        return X25519.publicFromPrivate(privateKey);
    }

    /**
     * Encrypts {@code plaintext} for every {@code recipients} device. The sender's
     * own devices must be included so history stays readable on this device.
     */
    @NonNull
    public EncryptedMessage encrypt(
            @NonNull String plaintext,
            @NonNull String conversationId,
            @NonNull String senderId,
            @NonNull String clientMessageId,
            @NonNull List<DevicePublicKey> recipients) throws GeneralSecurityException {
        if (recipients.isEmpty()) {
            throw new GeneralSecurityException("No recipient devices");
        }
        return encryptWithEphemeral(plaintext, conversationId, senderId, clientMessageId,
                recipients, X25519.generatePrivateKey());
    }

    /**
     * {@link #encrypt} with an explicit ephemeral private key. Exists so the
     * fixed-vector test can pin every input; production callers use
     * {@link #encrypt}, which always draws a fresh pair.
     */
    @VisibleForTesting
    EncryptedMessage encryptWithEphemeral(
            @NonNull String plaintext,
            @NonNull String conversationId,
            @NonNull String senderId,
            @NonNull String clientMessageId,
            @NonNull List<DevicePublicKey> recipients,
            @NonNull byte[] ephemeralPrivate) throws GeneralSecurityException {
        if (recipients.isEmpty()) {
            throw new GeneralSecurityException("No recipient devices");
        }
        byte[] contentKey = randomBytes(KEY_BYTES);
        byte[] nonce = randomBytes(NONCE_BYTES);
        byte[] aad = aad(conversationId, senderId, clientMessageId);
        byte[] ciphertext = aesGcm(contentKey, nonce, utf8(plaintext), aad, true);

        byte[] ephemeralPublic = X25519.publicFromPrivate(ephemeralPrivate);
        byte[] wire = concat(ephemeralPublic, nonce, ciphertext);
        String content = CONTENT_PREFIX + b64url(wire);

        List<Envelope> envelopes = new ArrayList<>(recipients.size());
        for (DevicePublicKey recipient : recipients) {
            byte[] kek = kek(ephemeralPrivate, recipient.publicKey(), clientMessageId,
                    recipient.deviceId());
            byte[] wrapNonce = randomBytes(NONCE_BYTES);
            byte[] wrapped = aesGcm(kek, wrapNonce, contentKey, aad, true);
            envelopes.add(new Envelope(recipient.deviceId(), b64url(concat(wrapNonce, wrapped))));
        }
        return new EncryptedMessage(content, List.copyOf(envelopes));
    }

    /**
     * Reverses {@link #encrypt} for {@code myDeviceId}. Returns empty on any
     * failure: callers must treat all bad input identically so a corrupt row
     * cannot crash ingest or leak detail to logs.
     */
    @NonNull
    public Optional<String> decrypt(
            @Nullable String content,
            @Nullable List<Envelope> envelopes,
            @Nullable String myDeviceId,
            @Nullable byte[] myPrivate,
            @NonNull String conversationId,
            @NonNull String senderId,
            @NonNull String clientMessageId) {
        try {
            if (content == null || !content.startsWith(CONTENT_PREFIX)
                    || envelopes == null || myDeviceId == null
                    || myPrivate == null || myPrivate.length != KEY_BYTES) {
                return Optional.empty();
            }
            byte[] wire = Base64.getUrlDecoder().decode(
                    content.substring(CONTENT_PREFIX.length()));
            if (wire.length <= KEY_BYTES + NONCE_BYTES) {
                return Optional.empty();
            }
            byte[] ephemeralPublic = Arrays.copyOfRange(wire, 0, KEY_BYTES);
            byte[] nonce = Arrays.copyOfRange(wire, KEY_BYTES, KEY_BYTES + NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(wire, KEY_BYTES + NONCE_BYTES, wire.length);

            Envelope mine = null;
            for (Envelope envelope : envelopes) {
                if (myDeviceId.equals(envelope.deviceId())) {
                    mine = envelope;
                    break;
                }
            }
            if (mine == null) {
                return Optional.empty();
            }
            byte[] wrapped = Base64.getUrlDecoder().decode(mine.wrappedKey());
            if (wrapped.length <= NONCE_BYTES) {
                return Optional.empty();
            }
            byte[] wrapNonce = Arrays.copyOfRange(wrapped, 0, NONCE_BYTES);
            byte[] wrappedCt = Arrays.copyOfRange(wrapped, NONCE_BYTES, wrapped.length);

            byte[] kek = kek(myPrivate, ephemeralPublic, clientMessageId, myDeviceId);
            byte[] aad = aad(conversationId, senderId, clientMessageId);
            byte[] contentKey = aesGcm(kek, wrapNonce, wrappedCt, aad, false);
            if (contentKey.length != KEY_BYTES) {
                return Optional.empty();
            }
            byte[] plaintext = aesGcm(contentKey, nonce, ciphertext, aad, false);
            return Optional.of(new String(plaintext, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** True when {@code content} carries the v1 wire prefix. */
    public static boolean isEncryptedWireContent(@Nullable String content) {
        return content != null && content.startsWith(CONTENT_PREFIX);
    }

    @VisibleForTesting
    static byte[] aad(@NonNull String conversationId, @NonNull String senderId,
            @NonNull String clientMessageId) {
        return utf8(AAD_PREFIX + conversationId + "|" + senderId + "|" + clientMessageId);
    }

    private static byte[] kek(byte[] myPrivate, byte[] peerPublic, String clientMessageId,
            String deviceId) throws GeneralSecurityException {
        byte[] shared = X25519.computeSharedSecret(myPrivate, peerPublic);
        return Hkdf.computeHkdf(HKDF_ALGORITHM, shared, utf8(clientMessageId),
                utf8(KEK_INFO_PREFIX + deviceId), KEY_BYTES);
    }

    private static byte[] aesGcm(byte[] key, byte[] nonce, byte[] input, byte[] aad,
            boolean encrypt) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    private byte[] randomBytes(int count) {
        byte[] out = new byte[count];
        random.nextBytes(out);
        return out;
    }

    @VisibleForTesting
    static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Base64url (no padding) decode; accepts padded input too for tolerance. */
    static byte[] b64urlDecode(@NonNull String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
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

    /** Renders bytes as lowercase hex; fixed-vector tests read this directly. */
    @VisibleForTesting
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }
}
