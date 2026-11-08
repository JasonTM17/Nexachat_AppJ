package com.nexachat.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Parsed Google service-account credentials. Holds the RSA private key in memory
 * only; {@link #toString()} never renders key material.
 */
final class ServiceAccountCredentials {

    private final String clientEmail;
    private final PrivateKey privateKey;
    private final String tokenUri;

    private ServiceAccountCredentials(String clientEmail, PrivateKey privateKey, String tokenUri) {
        this.clientEmail = clientEmail;
        this.privateKey = privateKey;
        this.tokenUri = tokenUri;
    }

    String clientEmail() {
        return clientEmail;
    }

    PrivateKey privateKey() {
        return privateKey;
    }

    String tokenUri() {
        return tokenUri;
    }

    /**
     * Accepts either inline JSON or a filesystem path to a JSON file. Failure
     * messages name the missing field only, never the offending value.
     */
    static ServiceAccountCredentials load(String configured, ObjectMapper objectMapper) {
        String json = readConfigured(configured);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("FCM service account JSON is malformed");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("FCM service account JSON is malformed");
        }
        String clientEmail = requiredText(root, "client_email");
        String tokenUri = requiredText(root, "token_uri");
        String privateKeyPem = requiredText(root, "private_key");
        return new ServiceAccountCredentials(clientEmail, parsePrivateKey(privateKeyPem), tokenUri);
    }

    private static String readConfigured(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("FCM is enabled but nexachat.fcm.service-account-json is empty");
        }
        String value = configured.strip();
        if (!value.startsWith("{") && looksLikePath(value)) {
            try {
                return Files.readString(Path.of(value), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new IllegalStateException("FCM service account file cannot be read: " + value);
            }
        }
        return value;
    }

    private static boolean looksLikePath(String value) {
        return value.endsWith(".json") || value.contains("/") || value.contains("\\");
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalStateException("FCM service account JSON is missing required field '" + field + "'");
        }
        return node.asText();
    }

    private static PrivateKey parsePrivateKey(String pem) {
        String normalized = pem.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder encoded = new StringBuilder();
        boolean inBody = false;
        for (String rawLine : normalized.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("-----")) {
                if (line.contains("BEGIN")) {
                    if (line.contains("RSA")) {
                        throw new IllegalStateException(
                                "FCM service account private key must be PKCS#8 PEM, not PKCS#1");
                    }
                    inBody = true;
                } else {
                    inBody = false;
                }
                continue;
            }
            if (inBody && !line.isEmpty()) {
                encoded.append(line);
            }
        }
        String body = encoded.toString();
        if (body.isEmpty()) {
            throw new IllegalStateException("FCM service account private key is empty");
        }
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("FCM service account private key is not valid base64 PEM");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("FCM service account private key is not a valid PKCS#8 RSA key");
        }
    }

    @Override
    public String toString() {
        return "ServiceAccountCredentials{clientEmail=[REDACTED]"
                + ", tokenUri='" + tokenUri + '\''
                + ", privateKey=[REDACTED]}";
    }
}
