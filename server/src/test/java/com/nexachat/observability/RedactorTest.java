package com.nexachat.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    @Test
    void masksBearerTokens() {
        String redacted = Redactor.redact(
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123def456");

        assertThat(redacted).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(redacted).doesNotContain("abc123def456");
        assertThat(redacted).contains("Bearer [REDACTED]");
    }

    @Test
    void masksStandaloneJwt() {
        String redacted = Redactor.redact(
                "token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature"); // gitleaks:allow

        assertThat(redacted).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(redacted).doesNotContain("signature");
    }

    @Test
    void masksNamedApiKeysAndSecrets() {
        String redacted = Redactor.redact(
                "api_key=test-key-123 password=test-pass-456 token=test-token-789");

        assertThat(redacted).doesNotContain("test-key-123");
        assertThat(redacted).doesNotContain("test-pass-456");
        assertThat(redacted).doesNotContain("test-token-789");
        assertThat(redacted).contains("api_key=[REDACTED]");
    }

    @Test
    void masksGoogleAndOpenAiKeyShapes() {
        String redacted = Redactor.redact(
                "google=AIzaSyA1234567890abcdefghijklmnopqrstu openai=sk-0123456789abcdefghijklmnop");

        assertThat(redacted).doesNotContain("AIzaSyA1234567890abcdefghijklmnopqrstu");
        assertThat(redacted).doesNotContain("sk-0123456789abcdefghijklmnop");
    }

    @Test
    void masksFcmRegistrationTokens() {
        String fcmToken = "f" + "a".repeat(150) + ":APA91b" + "b".repeat(120);
        String redacted = Redactor.redact("fcmToken=" + fcmToken);

        assertThat(redacted).doesNotContain(fcmToken);
        assertThat(redacted).contains("fcmToken=[REDACTED]");
    }

    @Test
    void masksPhoneNumbers() {
        String redacted = Redactor.redact(
                "login failed for +84912345678 and 0912345678, phone=+84901234567");

        assertThat(redacted).doesNotContain("84912345678");
        assertThat(redacted).doesNotContain("0912345678");
        assertThat(redacted).contains("[REDACTED]");
    }

    @Test
    void redactsEmailFields() {
        String redacted = Redactor.redact("contact lookup email=friend@example.com");
        assertThat(redacted).doesNotContain("friend@example.com");
        assertThat(redacted).contains("email=[REDACTED]");
    }

    @Test
    void masksMessageAndContentFields() {
        String redacted = Redactor.redact(
                "{\"content\":\"secret message body\",\"body\":\"另一个秘密\",\"text\":\"hidden\"}");

        assertThat(redacted).doesNotContain("secret message body");
        assertThat(redacted).doesNotContain("另一个秘密");
        assertThat(redacted).contains("\"content\":\"[REDACTED]\"");
        assertThat(redacted).contains("\"body\":\"[REDACTED]\"");
        assertThat(redacted).contains("\"text\":\"[REDACTED]\"");
    }

    @Test
    void masksContentParameters() {
        String redacted = Redactor.redact("query content=secret&limit=10");

        assertThat(redacted).doesNotContain("secret");
        assertThat(redacted).contains("content=[REDACTED]");
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        String message = "Message 123 (seq=456) stored in conversation by user";

        assertThat(Redactor.redact(message)).isEqualTo(message);
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertThat(Redactor.redact(null)).isNull();
        assertThat(Redactor.redact("")).isEmpty();
    }

    @Test
    void doesNotMaskTheWordAuthorizationAfterBearer() {
        String message = "STOMP CONNECT rejected: missing Bearer Authorization header";

        assertThat(Redactor.redact(message)).isEqualTo(message);
    }

    @Test
    void masksPrivateKeyPemBlocks() {
        String pem = pemBlock("PRIVATE KEY",
                "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7VJSSTUoFQo=");

        String redacted = Redactor.redact("{\"private_key\":\"" + pem.replace("\n", "\\n") + "\"}");

        assertThat(redacted).doesNotContain("MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7VJSSTUoFQo=");
        assertThat(redacted).doesNotContain("BEGIN "
                + "PRIVATE KEY");
        assertThat(redacted).doesNotContain("END PRIVATE KEY");
        assertThat(redacted).contains("[REDACTED]");
    }

    @Test
    void masksRsaPrivateKeyPemBlocksWithEscapedNewlines() {
        String pem = pemBlock("RSA PRIVATE KEY", "fake-body-0001");

        String redacted = Redactor.redact("{\"private_key\":\"" + pem.replace("\n", "\\n") + "\"}");

        assertThat(redacted).doesNotContain("fake-body-0001");
        assertThat(redacted).doesNotContain("BEGIN RSA "
                + "PRIVATE KEY");
        assertThat(redacted).contains("[REDACTED]");
    }

    @Test
    void masksEscapedQuotedPrivateKeyPemBlocks() {
        String pem = pemBlock("PRIVATE KEY", "MIIEsecretkeybody").replace("\n", "\\n");

        String redacted = Redactor.redact("{\\\"private_key\\\":\\\"" + pem + "\\\"}");

        assertThat(redacted).doesNotContain("MIIEsecretkeybody");
        assertThat(redacted).doesNotContain("BEGIN "
                + "PRIVATE KEY");
        assertThat(redacted).contains("[REDACTED]");
    }

    private static String pemBlock(String algorithm, String body) {
        return "-----BEGIN " + algorithm + "-----\n" + body + "\n-----END " + algorithm + "-----";
    }

    @Test
    void masksClientEmailFields() {
        String redacted = Redactor.redact("{\"client_email\":\"svc@project.iam.gserviceaccount.com\"}");

        assertThat(redacted).doesNotContain("svc@project.iam.gserviceaccount.com");
        assertThat(redacted).contains("client_email=[REDACTED]");
    }

    @Test
    void masksEscapedClientEmailFields() {
        String redacted = Redactor.redact(
                "{\\\"client_email\\\":\\\"svc@project.iam.gserviceaccount.com\\\"}");

        assertThat(redacted).doesNotContain("svc@project.iam.gserviceaccount.com");
        assertThat(redacted).contains("[REDACTED]");
    }

    @Test
    void masksPrivateKeyAssignedValues() {
        String redacted = Redactor.redact("private_key=abcdefghijklmnop");

        assertThat(redacted).doesNotContain("abcdefghijklmnop");
        assertThat(redacted).contains("private_key=[REDACTED]");
    }
}
