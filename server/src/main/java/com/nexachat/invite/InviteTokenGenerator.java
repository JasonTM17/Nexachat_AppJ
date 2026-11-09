package com.nexachat.invite;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Handles high-entropy token generation, SHA-256 hashing, and constant-time token
 * verification for group invite links. Raw tokens are never logged or stored plaintext.
 */
@Component
public class InviteTokenGenerator {

    private static final int TOKEN_BYTE_LENGTH = 32; // 256 bits of entropy
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a high-entropy URL-safe base64 string (no padding).
     */
    public String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Computes the deterministic SHA-256 hash of a token as a 64-character lowercase hex string.
     */
    public String hashToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Token must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /**
     * Performs a constant-time comparison between two hash strings to prevent timing attacks.
     */
    public boolean constantTimeEquals(String hash1, String hash2) {
        if (hash1 == null || hash2 == null) {
            return false;
        }
        byte[] b1 = hash1.getBytes(StandardCharsets.UTF_8);
        byte[] b2 = hash2.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(b1, b2);
    }
}
