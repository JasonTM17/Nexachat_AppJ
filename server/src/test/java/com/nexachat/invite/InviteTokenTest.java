package com.nexachat.invite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InviteTokenTest {

    private InviteTokenGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new InviteTokenGenerator();
    }

    @Test
    void generatesHighEntropyUrlSafeTokens() {
        int count = 1000;
        Set<String> tokens = new HashSet<>(count);
        for (int i = 0; i < count; i++) {
            String token = generator.generateToken();
            assertThat(token).isNotBlank();
            assertThat(token).matches("^[A-Za-z0-9_-]+$");
            assertThat(token).hasSize(43); // 32 bytes in URL-safe base64 without padding
            tokens.add(token);
        }
        assertThat(tokens).hasSize(count);
    }

    @Test
    void hashesTokensDeterministicallyWithSha256() {
        String token = generator.generateToken();
        String hash1 = generator.hashToken(token);
        String hash2 = generator.hashToken(token);

        assertThat(hash1)
                .isNotBlank()
                .hasSize(64)
                .matches("^[0-9a-f]{64}$");
        assertThat(hash1).isEqualTo(hash2);

        String otherToken = generator.generateToken();
        String otherHash = generator.hashToken(otherToken);
        assertThat(otherHash).isNotEqualTo(hash1);
    }

    @Test
    void rejectsNullOrBlankTokenForHashing() {
        assertThatThrownBy(() -> generator.hashToken(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.hashToken("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constantTimeEqualsVerifiesHashesCorrectly() {
        String token = generator.generateToken();
        String hash = generator.hashToken(token);
        String sameHash = generator.hashToken(token);
        String diffHash = generator.hashToken(generator.generateToken());

        assertThat(generator.constantTimeEquals(hash, sameHash)).isTrue();
        assertThat(generator.constantTimeEquals(hash, diffHash)).isFalse();
        assertThat(generator.constantTimeEquals(hash, null)).isFalse();
        assertThat(generator.constantTimeEquals(null, hash)).isFalse();
        assertThat(generator.constantTimeEquals(null, null)).isFalse();
        assertThat(generator.constantTimeEquals(hash, "short")).isFalse();
    }
}
