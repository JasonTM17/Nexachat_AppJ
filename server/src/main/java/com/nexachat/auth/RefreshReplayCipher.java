package com.nexachat.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.dto.AuthResponse;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** AES-256-GCM storage for a single original response attached to its predecessor row. */
@Component
public class RefreshReplayCipher {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final RefreshReplayProperties properties;

    public RefreshReplayCipher(ObjectMapper objectMapper, RefreshReplayProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public byte[] requireKey() {
        String configured = properties.getKey();
        if (configured == null || configured.isBlank()) {
            throw new KeyUnavailableException();
        }
        try {
            byte[] key = Base64.getDecoder().decode(configured);
            if (key.length != KEY_BYTES || !Base64.getEncoder().encodeToString(key).equals(configured)) {
                throw new KeyUnavailableException();
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new KeyUnavailableException();
        }
    }

    public boolean isKeyAvailable() {
        try {
            requireKey();
            return true;
        } catch (KeyUnavailableException exception) {
            return false;
        }
    }

    public static String keyDigest(UUID requestKey) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(requestKey.toString().getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public EncryptedPayload encrypt(AuthResponse response, UUID userId, UUID predecessorId,
                                    UUID successorId, String keyDigest, java.time.Instant rotatedAt,
                                    java.time.Instant replayUntil) {
        byte[] key = requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(userId, predecessorId, successorId, keyDigest, rotatedAt, replayUntil));
            return new EncryptedPayload(nonce, cipher.doFinal(objectMapper.writeValueAsBytes(response)));
        } catch (GeneralSecurityException | JsonProcessingException exception) {
            throw new CipherFailureException();
        }
    }

    public Optional<AuthResponse> decrypt(byte[] ciphertext, byte[] nonce, UUID userId, UUID predecessorId,
                                          UUID successorId, String keyDigest, java.time.Instant rotatedAt,
                                          java.time.Instant replayUntil) {
        if (ciphertext == null || nonce == null || nonce.length != NONCE_BYTES) {
            return Optional.empty();
        }
        final byte[] key;
        try {
            key = requireKey();
        } catch (KeyUnavailableException exception) {
            return Optional.empty();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(userId, predecessorId, successorId, keyDigest, rotatedAt, replayUntil));
            return Optional.of(objectMapper.readValue(cipher.doFinal(ciphertext), AuthResponse.class));
        } catch (AEADBadTagException exception) {
            return Optional.empty();
        } catch (GeneralSecurityException | IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static byte[] aad(UUID userId, UUID predecessorId, UUID successorId,
                              String keyDigest, java.time.Instant rotatedAt, java.time.Instant replayUntil) {
        byte[] ids = (userId + "\n" + predecessorId + "\n" + successorId + "\n" + keyDigest + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(ids.length + 24)
                .put(ids)
                .putLong(rotatedAt.getEpochSecond()).putInt(rotatedAt.getNano())
                .putLong(replayUntil.getEpochSecond()).putInt(replayUntil.getNano())
                .array();
    }

    public record EncryptedPayload(byte[] nonce, byte[] ciphertext) {
        public EncryptedPayload {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }

    public static final class KeyUnavailableException extends RuntimeException { }

    public static final class CipherFailureException extends RuntimeException { }
}
