package com.nexachat.recovery;

import com.nexachat.auth.JwtProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Component
final class OtpCodeHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] DUMMY_USER = "nexachat-otp-dummy-user".getBytes(StandardCharsets.UTF_8);
    private final SecretKeySpec key;

    OtpCodeHasher(JwtProperties jwtProperties) {
        if (jwtProperties.secret() == null || jwtProperties.secret().isBlank()) {
            throw new IllegalStateException("nexachat.jwt.secret is required for email OTPs");
        }
        byte[] derived = sha256("nexachat-email-otp-v1|" + jwtProperties.secret());
        this.key = new SecretKeySpec(derived, "HmacSHA256");
    }

    String newCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    String hash(UUID userId, OtpPurpose purpose, String code) {
        return HexFormat.of().formatHex(hmac(userId + "|" + purpose.name() + "|" + code));
    }

    boolean matches(String expectedHash, UUID userId, OtpPurpose purpose, String code) {
        String supplied = hash(userId, purpose, code == null ? "" : code);
        return MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }

    void dummyCompare(OtpPurpose purpose, String code) {
        byte[] userBytes = DUMMY_USER;
        byte[] supplied = hmac(new String(userBytes, StandardCharsets.UTF_8)
                + "|" + purpose.name() + "|" + (code == null ? "" : code));
        byte[] expected = hmac(new String(userBytes, StandardCharsets.UTF_8) + "|" + purpose.name() + "|000000");
        MessageDigest.isEqual(expected, supplied);
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
