package com.nexachat.feed;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedCursorTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-17T09:30:15.123456Z");
    private static final UUID ID = UUID.fromString("6f1c2a44-8b1e-4f5a-9c3d-2e7b0a1d4c55");

    @Test
    void roundTripsAPosition() {
        String token = FeedCursor.encode(new FeedCursor.Position(CREATED_AT, ID));

        FeedCursor.Position decoded = FeedCursor.decode(token);

        assertThat(decoded.createdAt()).isEqualTo(CREATED_AT);
        assertThat(decoded.id()).isEqualTo(ID);
    }

    @Test
    void tokenIsUrlSafeWithoutPadding() {
        String token = FeedCursor.encode(new FeedCursor.Position(CREATED_AT, ID));

        assertThat(token).doesNotContain("+", "/", "=");
        assertThat(Base64.getUrlDecoder().decode(token)).isNotEmpty();
    }

    @Test
    void rejectsATokenThatIsNotBase64() {
        assertThatThrownBy(() -> FeedCursor.decode("not a cursor!"))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void rejectsAnUnknownVersion() {
        String token = encode("v2|" + CREATED_AT + "|" + ID);

        assertThatThrownBy(() -> FeedCursor.decode(token))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void rejectsATokenWithTheWrongFieldCount() {
        String token = encode("v1|" + CREATED_AT);

        assertThatThrownBy(() -> FeedCursor.decode(token))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void rejectsATokenWithAnUnparseableInstant() {
        String token = encode("v1|yesterday|" + ID);

        assertThatThrownBy(() -> FeedCursor.decode(token))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void rejectsATokenWithAnUnparseableId() {
        String token = encode("v1|" + CREATED_AT + "|not-a-uuid");

        assertThatThrownBy(() -> FeedCursor.decode(token))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).getCode())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
