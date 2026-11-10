package com.nexachat.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexachat.google.GoogleServiceAccountCredentials;
import com.nexachat.google.GoogleServiceAccountCredentialsFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleServiceAccountCredentialsFactoryTest {

    private static final String CLIENT_EMAIL = "svc@project.iam.gserviceaccount.com";
    private static final String SEED_MATERIAL = "fake-service-account-material-0001";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rawInlineJsonLoads() {
        String pem = privateKeyPem();
        GoogleServiceAccountCredentials credentials =
                GoogleServiceAccountCredentialsFactory.load(serviceAccountJsonWithKey(pem), objectMapper);

        assertThat(credentials.clientEmail()).isEqualTo(CLIENT_EMAIL);
        assertThat(credentials.toString()).doesNotContain(pem);
    }

    @Test
    void doubleQuotedInlineJsonIsUnwrappedAndLoads() {
        String pem = privateKeyPem();
        String quoted = objectMapper.valueToTree(serviceAccountJsonWithKey(pem)).toString();

        GoogleServiceAccountCredentials credentials = GoogleServiceAccountCredentialsFactory.load(quoted, objectMapper);

        assertThat(credentials.clientEmail()).isEqualTo(CLIENT_EMAIL);
        assertThat(credentials.toString()).doesNotContain(pem);
    }

    @Test
    void singleQuotedInlineJsonIsUnwrappedAndLoads() {
        String quoted = "'" + serviceAccountJson() + "'";

        GoogleServiceAccountCredentials credentials = GoogleServiceAccountCredentialsFactory.load(quoted, objectMapper);

        assertThat(credentials.clientEmail()).isEqualTo(CLIENT_EMAIL);
    }

    @Test
    void bomPrefixedInlineJsonLoads() {
        GoogleServiceAccountCredentials credentials =
                GoogleServiceAccountCredentialsFactory.load("\uFEFF" + serviceAccountJson(), objectMapper);

        assertThat(credentials.clientEmail()).isEqualTo(CLIENT_EMAIL);
    }

    @Test
    void bomPrefixedQuotedInlineJsonLoads() {
        String quoted = objectMapper.valueToTree(serviceAccountJson()).toString();

        GoogleServiceAccountCredentials credentials =
                GoogleServiceAccountCredentialsFactory.load("\uFEFF" + quoted, objectMapper);

        assertThat(credentials.clientEmail()).isEqualTo(CLIENT_EMAIL);
    }

    @Test
    void quotedInlineJsonWithABadKeyDoesNotLeakTheKeyMaterial() {
        String quoted = objectMapper.valueToTree(serviceAccountJsonWithKey(SEED_MATERIAL)).toString();

        assertThatThrownBy(() -> GoogleServiceAccountCredentialsFactory.load(quoted, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SEED_MATERIAL)
                .hasMessageNotContaining(CLIENT_EMAIL)
                .hasMessageNotContaining("https://oauth2.googleapis.com/token");
    }

    @Test
    void bomPrefixedQuotedInlineJsonWithABadKeyDoesNotLeakTheKeyMaterial() {
        String quoted = objectMapper.valueToTree(serviceAccountJsonWithKey(SEED_MATERIAL)).toString();

        assertThatThrownBy(() -> GoogleServiceAccountCredentialsFactory.load("\uFEFF" + quoted, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SEED_MATERIAL)
                .hasMessageNotContaining(CLIENT_EMAIL);
    }

    @Test
    void unreadableFileExceptionNamesOnlyTheBasename() {
        String configured = "missing-dir/service-account.json";

        assertThatThrownBy(() -> GoogleServiceAccountCredentialsFactory.load(configured, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service-account.json")
                .hasMessageNotContaining("missing-dir");
    }

    @Test
    void quotedUnreadableFileExceptionNamesOnlyTheBasename() {
        String configured = "\"missing-dir/service-account.json\"";

        assertThatThrownBy(() -> GoogleServiceAccountCredentialsFactory.load(configured, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service-account.json")
                .hasMessageNotContaining("missing-dir");
    }

    @Test
    void googleCredentialRefreshDoesNotRetryAnUnsuccessfulTokenExchange() {
        try (FakeGoogleServers google = new FakeGoogleServers()) {
            google.setTokenStatus(500);
            GoogleServiceAccountCredentials credentials = GoogleServiceAccountCredentialsFactory.load(
                    google.serviceAccountJson(), objectMapper);

            assertThatThrownBy(() -> credentials.googleCredentials()
                    .createScoped(Set.of("https://www.googleapis.com/auth/firebase.database"))
                    .refreshAccessToken())
                    .isInstanceOf(IOException.class);
            assertThat(google.tokenRequestBodies()).hasSize(1);
        }
    }

    private String serviceAccountJson() {
        return serviceAccountJsonWithKey(privateKeyPem());
    }

    private String serviceAccountJsonWithKey(String privateKeyPem) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "service_account");
        node.put("project_id", "project");
        node.put("private_key_id", "test-key-id");
        node.put("client_email", CLIENT_EMAIL);
        node.put("client_id", "123456789012345678901");
        node.put("auth_uri", "https://accounts.google.com/o/oauth2/auth");
        node.put("token_uri", "https://oauth2.googleapis.com/token");
        node.put("auth_provider_x509_cert_url", "https://www.googleapis.com/oauth2/v1/certs");
        node.put("client_x509_cert_url", "https://www.googleapis.com/robot/v1/metadata/x509/svc%40project.iam.gserviceaccount.com");
        node.put("universe_domain", "googleapis.com");
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
            return "-----BEGIN "
                + "PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
