package com.nexachat.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceAccountCredentialsTest {

    private static final String CLIENT_EMAIL = "svc@project.iam.gserviceaccount.com";
    private static final String SEED_MATERIAL = "fake-service-account-material-0001";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rawInlineJsonLoads() {
        String pem = privateKeyPem();
        ServiceAccountCredentials credentials =
                ServiceAccountCredentials.load(serviceAccountJsonWithKey(pem), objectMapper);

        assertThat(credentials.clientEmail()).isEqualTo(CLIENT_EMAIL);
        assertThat(credentials.toString()).doesNotContain(pem);
    }

    @Test
    void doubleQuotedInlineJsonIsUnwrappedAndLoads() {
        String pem = privateKeyPem();
        String quoted = objectMapper.valueToTree(serviceAccountJsonWithKey(pem)).toString();

        ServiceAccountCredentials credentials = ServiceAccountCredentials.load(quoted, objectMapper);

        assertThat(credentials.clientEmail()).isEqualTo(CLIENT_EMAIL);
        assertThat(credentials.toString()).doesNotContain(pem);
    }

    @Test
    void singleQuotedInlineJsonIsUnwrappedAndLoads() {
        String quoted = "'" + serviceAccountJson() + "'";

        ServiceAccountCredentials credentials = ServiceAccountCredentials.load(quoted, objectMapper);

        assertThat(credentials.clientEmail()).isEqualTo(CLIENT_EMAIL);
    }

    @Test
    void bomPrefixedInlineJsonLoads() {
        ServiceAccountCredentials credentials =
                ServiceAccountCredentials.load("\uFEFF" + serviceAccountJson(), objectMapper);

        assertThat(credentials.clientEmail()).isEqualTo(CLIENT_EMAIL);
    }

    @Test
    void bomPrefixedQuotedInlineJsonLoads() {
        String quoted = objectMapper.valueToTree(serviceAccountJson()).toString();

        ServiceAccountCredentials credentials = ServiceAccountCredentials.load("\uFEFF" + quoted, objectMapper);

        assertThat(credentials.clientEmail()).isEqualTo(CLIENT_EMAIL);
    }

    @Test
    void quotedInlineJsonWithABadKeyDoesNotLeakTheKeyMaterial() {
        String quoted = objectMapper.valueToTree(serviceAccountJsonWithKey(SEED_MATERIAL)).toString();

        assertThatThrownBy(() -> ServiceAccountCredentials.load(quoted, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SEED_MATERIAL)
                .hasMessageNotContaining(CLIENT_EMAIL)
                .hasMessageNotContaining("https://oauth2.googleapis.com/token");
    }

    @Test
    void bomPrefixedQuotedInlineJsonWithABadKeyDoesNotLeakTheKeyMaterial() {
        String quoted = objectMapper.valueToTree(serviceAccountJsonWithKey(SEED_MATERIAL)).toString();

        assertThatThrownBy(() -> ServiceAccountCredentials.load("\uFEFF" + quoted, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SEED_MATERIAL)
                .hasMessageNotContaining(CLIENT_EMAIL);
    }

    @Test
    void unreadableFileExceptionNamesOnlyTheBasename() {
        String configured = "missing-dir/service-account.json";

        assertThatThrownBy(() -> ServiceAccountCredentials.load(configured, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service-account.json")
                .hasMessageNotContaining("missing-dir");
    }

    @Test
    void quotedUnreadableFileExceptionNamesOnlyTheBasename() {
        String configured = "\"missing-dir/service-account.json\"";

        assertThatThrownBy(() -> ServiceAccountCredentials.load(configured, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service-account.json")
                .hasMessageNotContaining("missing-dir");
    }

    private String serviceAccountJson() {
        return serviceAccountJsonWithKey(privateKeyPem());
    }

    private String serviceAccountJsonWithKey(String privateKeyPem) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "service_account");
        node.put("client_email", CLIENT_EMAIL);
        node.put("token_uri", "https://oauth2.googleapis.com/token");
        node.put("private_key", privateKeyPem);
        return node.toString();
    }

    private static String privateKeyPem() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(keyPair.getPrivate().getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
