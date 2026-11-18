package com.nexachat.feed;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursor for the feed pages: {@code v1|createdAt|id}, base64url
 * without padding. The same codec serves the newest-first post page and the
 * oldest-first comment page; the direction lives in the query, not the token.
 * A malformed token is a client bug and fails {@code VALIDATION_FAILED}.
 */
final class FeedCursor {

    record Position(Instant createdAt, UUID id) {
    }

    private FeedCursor() {
    }

    static String encode(Position position) {
        String value = "v1|" + position.createdAt() + "|" + position.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static Position decode(String token) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] fields = value.split("\\|", -1);
            if (fields.length != 3 || !"v1".equals(fields[0])) {
                throw new IllegalArgumentException("invalid cursor version");
            }
            return new Position(Instant.parse(fields[1]), UUID.fromString(fields[2]));
        } catch (RuntimeException failure) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Invalid feed cursor");
        }
    }
}
