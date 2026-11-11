package com.nexachat.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.user.dto.UserResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class RefreshReplayCipherTest {

    private static String syntheticKey() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void encryptsWholeResponseWithFreshNonceAndBoundAssociatedData() throws Exception {
        RefreshReplayProperties properties = new RefreshReplayProperties();
        properties.setKey(syntheticKey());
        ObjectMapper mapper = new ObjectMapper();
        RefreshReplayCipher cipher = new RefreshReplayCipher(mapper, properties);
        UUID userId = UUID.randomUUID();
        UUID predecessorId = UUID.randomUUID();
        UUID successorId = UUID.randomUUID();
        String digest = "a".repeat(64);
        Instant rotatedAt = Instant.parse("2026-11-10T08:00:00Z");
        Instant replayUntil = rotatedAt.plusSeconds(30);
        AuthResponse response = new AuthResponse(
                new UserResponse(userId, "+84900000001", "Synthetic", null, false),
                "synthetic-access-token", 900, "synthetic-refresh-token", 86_400, true);

        RefreshReplayCipher.EncryptedPayload first = cipher.encrypt(
                response, userId, predecessorId, successorId, digest, rotatedAt, replayUntil);
        RefreshReplayCipher.EncryptedPayload second = cipher.encrypt(
                response, userId, predecessorId, successorId, digest, rotatedAt, replayUntil);

        assertThat(first.nonce()).hasSize(12);
        assertThat(!java.util.Arrays.equals(first.nonce(), second.nonce())).isTrue();
        byte[] serialized = mapper.writeValueAsBytes(response);
        assertThat(!java.util.Arrays.equals(first.ciphertext(), serialized)).isTrue();
        Optional<AuthResponse> recovered = cipher.decrypt(first.ciphertext(), first.nonce(), userId,
                predecessorId, successorId, digest, rotatedAt, replayUntil);
        assertThat(recovered.isPresent()).isTrue();
        assertThat(response.equals(recovered.orElse(null))).isTrue();

        assertThat(cipher.decrypt(first.ciphertext(), first.nonce(), UUID.randomUUID(),
                predecessorId, successorId, digest, rotatedAt, replayUntil).isEmpty()).isTrue();
        assertThat(cipher.decrypt(first.ciphertext(), first.nonce(), userId,
                predecessorId, successorId, digest, rotatedAt.plusNanos(1), replayUntil).isEmpty()).isTrue();

        byte[] tampered = first.ciphertext();
        tampered[0] ^= 1;
        assertThat(cipher.decrypt(tampered, first.nonce(), userId,
                predecessorId, successorId, digest, rotatedAt, replayUntil).isEmpty()).isTrue();
    }

    @Test
    void missingInvalidOrChangedKeyCannotDecryptOrBeUsedForEncryption() {
        RefreshReplayProperties properties = new RefreshReplayProperties();
        RefreshReplayCipher cipher = new RefreshReplayCipher(new ObjectMapper(), properties);
        RefreshReplayCipher.KeyUnavailableException missing = catchThrowableOfType(
                RefreshReplayCipher.KeyUnavailableException.class, cipher::requireKey);
        assertThat(missing).isNotNull();

        properties.setKey("not-base64!");
        RefreshReplayCipher.KeyUnavailableException malformed = catchThrowableOfType(
                RefreshReplayCipher.KeyUnavailableException.class, cipher::requireKey);
        assertThat(malformed).isNotNull();

        properties.setKey(syntheticKey());
        UUID userId = UUID.randomUUID();
        UUID predecessorId = UUID.randomUUID();
        UUID successorId = UUID.randomUUID();
        Instant rotatedAt = Instant.parse("2026-11-10T08:00:00Z");
        String digest = "b".repeat(64);
        RefreshReplayCipher.EncryptedPayload payload = cipher.encrypt(
                new AuthResponse(new UserResponse(userId, "+84900000001", "Synthetic", null, false),
                        "a", 900, "r", 86_400, true), userId, predecessorId, successorId, digest,
                rotatedAt, rotatedAt.plusSeconds(30));
        properties.setKey(Base64.getEncoder().encodeToString(new byte[32]));
        assertThat(cipher.decrypt(payload.ciphertext(), payload.nonce(), userId,
                predecessorId, successorId, digest, rotatedAt, rotatedAt.plusSeconds(30)).isEmpty()).isTrue();
    }
}
