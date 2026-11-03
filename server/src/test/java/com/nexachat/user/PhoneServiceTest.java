package com.nexachat.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Real libphonenumber — no mocks. Covers the E.164 boundary contract (T18):
 * local VN format, international format, and rejection of garbage input.
 */
class PhoneServiceTest {

    private final PhoneService phoneService = new PhoneService();

    @Test
    void toE164_vietnameseLocalNumber_normalizesToE164() {
        assertThat(phoneService.toE164("0912345678")).isEqualTo("+84912345678");
        assertThat(phoneService.toE164("0987654321")).isEqualTo("+84987654321");
    }

    @Test
    void toE164_internationalInput_passesThroughNormalized() {
        assertThat(phoneService.toE164("+84912345678")).isEqualTo("+84912345678");
        // Formatting separators do not defeat the parser.
        assertThat(phoneService.toE164("+84 912 345 678")).isEqualTo("+84912345678");
    }

    @Test
    void toE164_garbageInput_throwsIllegalArgument() {
        assertThat(catchThrowableOfType(IllegalArgumentException.class, () -> phoneService.toE164("not-a-phone")))
                .isNotNull();
        assertThat(catchThrowableOfType(IllegalArgumentException.class, () -> phoneService.toE164("123")))
                .isNotNull();
        assertThat(catchThrowableOfType(IllegalArgumentException.class, () -> phoneService.toE164("  ")))
                .isNotNull();
        assertThat(catchThrowableOfType(IllegalArgumentException.class, () -> phoneService.toE164(null)))
                .isNotNull();
    }
}
