package com.nexachat.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BcryptBoundedPasswordEncoderTest {

    @Test
    void rawBcryptEncodeThrowsAndMatchesSkips72ByteLimitOnCheckPath() {
        BCryptPasswordEncoder raw = new BCryptPasswordEncoder(4);
        String exact72 = "a".repeat(72);
        String hash72 = raw.encode(exact72);

        assertThatThrownBy(() -> raw.encode("a".repeat(73)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("72 bytes");

        // checkpw uses hashpwforcheck(..., skipValidation=true), so matches neither throws
        // nor fails closed — a 73-byte secret can match the 72-byte hash (prefix truncation).
        String over73 = exact72 + "x";
        assertThat(over73.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);
        assertThat(raw.matches(over73, hash72)).isTrue();

        BcryptBoundedPasswordEncoder bounded = new BcryptBoundedPasswordEncoder(raw);
        assertThat(bounded.matches(over73, hash72)).isFalse();
    }

    @Test
    void wrapperMatchesAcceptsExactly72BytesAndRejectsLongerInput() {
        BCryptPasswordEncoder inner = new BCryptPasswordEncoder(4);
        BcryptBoundedPasswordEncoder encoder = new BcryptBoundedPasswordEncoder(inner);

        String exact72 = "a".repeat(72);
        assertThat(exact72.getBytes(StandardCharsets.UTF_8).length).isEqualTo(72);
        String hash72 = encoder.encode(exact72);
        assertThat(encoder.matches(exact72, hash72)).isTrue();

        String over73 = "a".repeat(73);
        assertThat(encoder.matches(over73, hash72)).isFalse();
    }

    @Test
    void wrapperMatchesUsesUtf8ByteCountForMultiByteSecrets() {
        BCryptPasswordEncoder inner = new BCryptPasswordEncoder(4);
        BcryptBoundedPasswordEncoder encoder = new BcryptBoundedPasswordEncoder(inner);

        String thirtySixChars = "é".repeat(36);
        assertThat(thirtySixChars.getBytes(StandardCharsets.UTF_8).length).isEqualTo(72);
        String hash = encoder.encode(thirtySixChars);
        assertThat(encoder.matches(thirtySixChars, hash)).isTrue();

        String thirtySevenChars = "é".repeat(37);
        assertThat(thirtySevenChars.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);
        assertThat(encoder.matches(thirtySevenChars, hash)).isFalse();
    }

    @Test
    void wrapperMatchesNullOrNulReturnsFalseWithoutThrowing() {
        BCryptPasswordEncoder inner = new BCryptPasswordEncoder(4);
        BcryptBoundedPasswordEncoder encoder = new BcryptBoundedPasswordEncoder(inner);
        String hash = encoder.encode("ok");

        assertThat(encoder.matches(null, hash)).isFalse();
        assertThat(encoder.matches("secret\u0000tail", hash)).isFalse();
    }

    @Test
    void wrapperEncodeStillRejectsOverLongSecrets() {
        BcryptBoundedPasswordEncoder encoder =
                new BcryptBoundedPasswordEncoder(new BCryptPasswordEncoder(4));
        assertThatThrownBy(() -> encoder.encode("a".repeat(73)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("72 bytes");
    }
}
