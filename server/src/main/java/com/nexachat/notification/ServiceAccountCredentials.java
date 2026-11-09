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
        String json = readConfigured(configured, objectMapper);
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

    private static String readConfigured(String configured, ObjectMapper objectMapper) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("FCM is enabled but nexachat.fcm.service-account-json is empty");
        }
        String value = stripBom(configured.strip());
        String inlineJson = inlineJson(value, objectMapper);
        if (inlineJson != null) {
            return inlineJson;
        }
        String pathValue = stripQuotes(value);
        if (looksLikePath(pathValue)) {
            try {
                return Files.readString(Path.of(pathValue), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "FCM service account file cannot be read: " + displayName(pathValue));
            }
        }
        return value;
    }

    /**
     * Inline JSON is accepted when the value starts with an object or array, or
     * when a deployment template wrapped the JSON in quotes (optionally behind a
     * UTF-8 BOM). A double-quoted value is unescaped by the parser so the
     * original JSON text reaches {@link #load}.
     */
    private static String inlineJson(String value, ObjectMapper objectMapper) {
        String candidate = value;
        if (isQuoted(candidate, '\'')) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.startsWith("{") || candidate.startsWith("[")) {
            return candidate;
        }
        if (isQuoted(candidate, '"')) {
            try {
                JsonNode node = objectMapper.readTree(candidate);
                if (node != null && node.isTextual()) {
                    String inner = node.asText();
                    if (inner != null && (inner.startsWith("{") || inner.startsWith("["))) {
                        return inner;
                    }
                }
            } catch (Exception ignored) {
                // Not a quoted JSON document; fall through to path handling.
            }
        }
        return null;
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static String stripQuotes(String value) {
        if (isQuoted(value, '"') || isQuoted(value, '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean isQuoted(String value, char quote) {
        return value.length() >= 2 && value.charAt(0) == quote && value.charAt(value.length() - 1) == quote;
    }

    /**
     * A path is reported by file name only: the configured value can be a
     * filesystem path with host-specific directories, and the value itself must
     * never reach a log or an exception message.
     */
    private static String displayName(String pathValue) {
        try {
            Path fileName = Path.of(pathValue).getFileName();
            if (fileName != null && !fileName.toString().isBlank()) {
                return fileName.toString();
            }
        } catch (RuntimeException ignored) {
            // Not a usable path; fall through to the fixed label.
        }
        return "configured service-account file";
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
