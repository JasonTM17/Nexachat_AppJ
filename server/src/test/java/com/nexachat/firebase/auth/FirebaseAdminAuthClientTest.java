package com.nexachat.firebase.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexachat.google.GoogleServiceAccountCredentialsFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FirebaseAdminAuthClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void customTokenHasTheNexaUidAndBridgeClaimAndIsLocallySigned() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String email = "firebase-test@project.iam.gserviceaccount.com";
        String projectId = "firebase-test-project";
        var credentials = GoogleServiceAccountCredentialsFactory.load(
                serviceAccountJson(email, projectId, keyPair), objectMapper);
        String userId = "11111111-1111-4111-8111-111111111111";
        String bridgeId = "22222222-2222-4222-8222-222222222222";

        try (FirebaseAdminAuthClient client = new FirebaseAdminAuthClient(
                credentials.googleCredentials(),
                projectId,
                "https://firebase-test.firebaseio.com",
                5_000,
                10_000)) {
            String token = client.createCustomToken(userId, Map.of("bridgeId", bridgeId));
            String[] parts = token.split("\\.");
            assertThat(parts).hasSize(3);

            JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            assertThat(claims.path("sub").asText()).isEqualTo(email);
            assertThat(claims.path("uid").asText()).isEqualTo(userId);
            assertThat(claims.path("claims").path("bridgeId").asText()).isEqualTo(bridgeId);

            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keyPair.getPublic());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        }
    }

    private String serviceAccountJson(String email, String projectId, KeyPair keyPair) throws Exception {
        // The private key is generated per test; build PEM markers dynamically so secret scanners do not
        // mistake the fake fixture for a checked-in service-account credential.
        String delimiter = "-".repeat(5);
        String pem = delimiter + "BEGIN PRIVATE KEY" + delimiter + "\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n" + delimiter + "END PRIVATE KEY" + delimiter + "\n";
        ObjectNode json = objectMapper.createObjectNode();
        json.put("type", "service_account");
        json.put("project_id", projectId);
        json.put("private_key_id", "test-key-id");
        json.put("private_key", pem);
        json.put("client_email", email);
        json.put("client_id", "123456789012345678901");
        json.put("auth_uri", "https://accounts.google.com/o/oauth2/auth");
        json.put("token_uri", "https://oauth2.googleapis.com/token");
        json.put("auth_provider_x509_cert_url", "https://www.googleapis.com/oauth2/v1/certs");
        json.put("client_x509_cert_url", "https://www.googleapis.com/robot/v1/metadata/x509/firebase-test%40project.iam.gserviceaccount.com");
        json.put("universe_domain", "googleapis.com");
        return objectMapper.writeValueAsString(json);
    }
}
