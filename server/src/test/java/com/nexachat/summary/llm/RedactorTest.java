package com.nexachat.summary.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    private final Redactor redactor = new Redactor();

    @Test
    void removesContactDetailsUrlSecretsLongNumbersAndTokenLikeStrings() {
        String input = "Call +84 912-345-678 or 0912345678, email ana.nguyen@example.com, "
                + "open https://example.com/private/path?access_token=secret, "
                + "visit example.org/reset?code=hidden, ref 1234567890123456789012345 and "
                + "token abcDEF0123456789_QWERTY-1234567890.";

        String output = redactor.redact(input);

        assertThat(output).contains("example.com", "example.org", "[REDACTED_EMAIL]", "[REDACTED_PHONE]");
        assertThat(output).contains("[REDACTED_NUMBER]", "[REDACTED_TOKEN]");
        assertThat(output).doesNotContain("912-345-678", "0912345678", "ana.nguyen@example.com",
                "/private/path", "access_token", "reset?code", "1234567890123456789012345", "abcDEF0123456789");
    }

    @Test
    void removesVietnamesePhoneFormatsAndUrlCredentials() {
        String input = "Gọi 0912 345 678 hoặc 0912345678, (+84) 912 345 678, "
                + "+84-912-345-678, xem https://user:pass@example.com/secret/path";

        String output = redactor.redact(input);

        assertThat(output).contains("[REDACTED_PHONE]", "example.com");
        assertThat(output).doesNotContain("0912 345 678", "0912345678", "912 345 678",
                "user:pass", "/secret/path");
    }

    @Test
    void removesUnicodeDecimalDigitPhoneNumbers() {
        String arabicIndic = "٠٩١٢٣٤٥٦٧٨";
        String fullWidth = "０９１２３４５６７８";

        String output = redactor.redact("Call " + arabicIndic + " or " + fullWidth);

        assertThat(output).contains("[REDACTED_PHONE]");
        assertThat(output).doesNotContain(arabicIndic, fullWidth);
    }

    @Test
    void removesSupplementaryPlaneDecimalDigitPhoneNumbers() {
        String supplementaryDigits = "𝟘𝟡𝟙-𝟚𝟛𝟜-𝟝𝟞𝟟𝟠";

        String output = redactor.redact("Call " + supplementaryDigits);

        assertThat(output).contains("[REDACTED_PHONE]");
        assertThat(output).doesNotContain(supplementaryDigits);
    }

    @Test
    void preservesPlainConversationTextAndUrlDomain() {
        assertThat(redactor.redact("We will meet tomorrow at school.example.vn."))
                .isEqualTo("We will meet tomorrow at school.example.vn.");
    }
}
