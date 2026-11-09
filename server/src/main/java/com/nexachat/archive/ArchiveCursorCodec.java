package com.nexachat.archive;

import com.nexachat.auth.JwtProperties;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * Mints and verifies the opaque archive cursor.
 *
 * <p>The cursor is a keyset position, never an authorization token: membership
 * is re-checked on every request. It is nevertheless HMAC-signed so a client
 * cannot forge a position for another conversation, another tab or an
 * arbitrary sequence by editing the payload; any malformed or altered value is
 * rejected as {@code 400 VALIDATION_FAILED} instead of being trusted.
 *
 * <p>Payload layout: {@code v1|KIND|conversationId|seq|sub}, signed with
 * HMAC-SHA256. The signing key is derived from the configured JWT secret with
 * a domain-separation label, so no extra deployment secret is needed and the
 * raw JWT key is not used directly.
 */
@Component
public class ArchiveCursorCodec {

    private static final String VERSION = "v1";
    private static final String KEY_LABEL = "nexachat.archive.cursor.v1:";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAX_CURSOR_LENGTH = 1024;

    private final SecretKeySpec key;

    public ArchiveCursorCodec(JwtProperties properties) {
        String secret = properties == null ? null : properties.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("nexachat.jwt.secret is required for archive cursors");
        }
        this.key = new SecretKeySpec(sha256(KEY_LABEL + secret), HMAC_ALGORITHM);
    }

    /**
     * @return the signed, URL-safe cursor for the position *before* the next
     *         item (the client echoes it as {@code cursor})
     */
    public String encode(UUID conversationId, ArchiveKind kind, long seq, String sub) {
        if (conversationId == null || kind == null || seq <= 0 || sub == null) {
            throw new IllegalArgumentException("cursor fields must be present and seq positive");
        }
        String payload = VERSION + "|" + kind.name() + "|" + conversationId + "|" + seq + "|" + sub;
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return encoder.encodeToString(payloadBytes) + "." + encoder.encodeToString(sign(payloadBytes));
    }

    /**
     * Verifies and decodes {@code raw}. A {@code null}/blank value means "first
     * page" and returns {@code null}.
     *
     * @throws ApiException {@code VALIDATION_FAILED} when the cursor is
     *                      malformed, was altered, or belongs to another
     *                      conversation/tab
     */
    public ArchiveCursor decode(String raw, UUID expectedConversationId, ArchiveKind expectedKind) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        String[] parts = raw.split("\\.", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw invalidCursor();
        }
        byte[] payloadBytes;
        byte[] suppliedSignature;
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            payloadBytes = decoder.decode(parts[0]);
            suppliedSignature = decoder.decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
        if (!MessageDigest.isEqual(sign(payloadBytes), suppliedSignature)) {
            throw invalidCursor();
        }

        String[] fields = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
        if (fields.length != 5 || !VERSION.equals(fields[0])) {
            throw invalidCursor();
        }
        final ArchiveKind kind;
        try {
            kind = ArchiveKind.valueOf(fields[1]);
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
        if (kind != expectedKind) {
            throw invalidCursor();
        }
        UUID conversationId;
        try {
            conversationId = UUID.fromString(fields[2]);
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
        if (!conversationId.equals(expectedConversationId)) {
            throw invalidCursor();
        }
        long seq;
        try {
            seq = Long.parseLong(fields[3]);
        } catch (NumberFormatException e) {
            throw invalidCursor();
        }
        if (seq <= 0) {
            throw invalidCursor();
        }
        String sub = fields[4];
        if (kind == ArchiveKind.LINK) {
            try {
                int offset = Integer.parseInt(sub);
                if (offset < -1) {
                    throw invalidCursor();
                }
            } catch (NumberFormatException e) {
                throw invalidCursor();
            }
        } else {
            try {
                UUID.fromString(sub);
            } catch (IllegalArgumentException e) {
                throw invalidCursor();
            }
        }
        return new ArchiveCursor(conversationId, kind, seq, sub);
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static ApiException invalidCursor() {
        return ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Invalid cursor");
    }
}
