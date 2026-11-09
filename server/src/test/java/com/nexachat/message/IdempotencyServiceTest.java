package com.nexachat.message;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link IdempotencyService}: the replay contract (same key +
 * endpoint + payload is a no-op), the key-reuse guard (different endpoint or
 * payload is 409), key validation, and the concurrent-insert race.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private RequestIdempotencyRepository repository;

    private IdempotencyService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository);
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private RequestIdempotency row(String endpoint, String payload) {
        RequestIdempotency row = new RequestIdempotency();
        org.springframework.test.util.ReflectionTestUtils.setField(row, "endpoint", endpoint);
        org.springframework.test.util.ReflectionTestUtils.setField(row, "payloadHash", hash(payload));
        return row;
    }

    @Test
    void absentKey_runsActionAndStoresNothing() {
        AtomicInteger runs = new AtomicInteger();

        service.execute(userId, null, "POST /x", "p", runs::incrementAndGet);

        assertThat(runs).hasValue(1);
        verify(repository, never()).insert(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void firstRequest_runsActionAndStoresRow() {
        when(repository.findByUserIdAndKey(userId, key.toString())).thenReturn(Optional.empty());
        when(repository.insert(eq(userId), eq(key.toString()), eq("POST /x"), anyString(), anyString()))
                .thenReturn(1);
        AtomicInteger runs = new AtomicInteger();

        service.execute(userId, key.toString(), "POST /x", "p", runs::incrementAndGet);

        assertThat(runs).hasValue(1);
        verify(repository).insert(eq(userId), eq(key.toString()), eq("POST /x"), eq(hash("p")), anyString());
    }

    @Test
    void replaySameKeyEndpointAndPayload_skipsAction() {
        when(repository.findByUserIdAndKey(userId, key.toString()))
                .thenReturn(Optional.of(row("POST /x", "p")));
        AtomicInteger runs = new AtomicInteger();

        service.execute(userId, key.toString(), "POST /x", "p", runs::incrementAndGet);

        assertThat(runs).hasValue(0);
        verify(repository, never()).insert(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sameKeyDifferentEndpoint_throwsKeyReused() {
        when(repository.findByUserIdAndKey(userId, key.toString()))
                .thenReturn(Optional.of(row("POST /x", "p")));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.execute(userId, key.toString(), "DELETE /x", "p", () -> {
                }));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(ex.getHttpStatus()).isEqualTo(409);
    }

    @Test
    void sameKeyDifferentPayload_throwsKeyReused() {
        when(repository.findByUserIdAndKey(userId, key.toString()))
                .thenReturn(Optional.of(row("POST /x", "p")));

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.execute(userId, key.toString(), "POST /x", "other", () -> {
                }));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void sameKeyForAnotherUser_isIsolated() {
        when(repository.findByUserIdAndKey(otherUserId, key.toString())).thenReturn(Optional.empty());
        when(repository.insert(eq(otherUserId), eq(key.toString()), eq("POST /x"), anyString(), anyString()))
                .thenReturn(1);
        AtomicInteger runs = new AtomicInteger();

        service.execute(otherUserId, key.toString(), "POST /x", "p", runs::incrementAndGet);

        assertThat(runs).hasValue(1);
        verify(repository).insert(eq(otherUserId), eq(key.toString()), eq("POST /x"), eq(hash("p")), anyString());
    }

    @Test
    void oversizedKey_throwsValidation() {
        String oversized = UUID.randomUUID() + "-padding-padding-padding-padding-padding";

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.execute(userId, oversized, "POST /x", "p", () -> {
                }));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        verify(repository, never()).findByUserIdAndKey(eq(userId), anyString());
    }

    @Test
    void nonUuidKey_throwsValidation() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.execute(userId, "not-a-uuid", "POST /x", "p", () -> {
                }));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void conflictingReservationWithSameOperation_skipsAction() {
        when(repository.findByUserIdAndKey(userId, key.toString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(row("POST /x", "p")));
        when(repository.insert(eq(userId), eq(key.toString()), eq("POST /x"), anyString(), anyString()))
                .thenReturn(0);
        AtomicInteger runs = new AtomicInteger();

        service.execute(userId, key.toString(), "POST /x", "p", runs::incrementAndGet);

        assertThat(runs).hasValue(0);
    }

    @Test
    void concurrentWinnerWithDifferentOperation_throwsKeyReused() {
        when(repository.findByUserIdAndKey(userId, key.toString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(row("DELETE /x", "p")));
        when(repository.insert(eq(userId), eq(key.toString()), eq("POST /x"), anyString(), anyString()))
                .thenReturn(0);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> service.execute(userId, key.toString(), "POST /x", "p", () -> {
                }));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
}
