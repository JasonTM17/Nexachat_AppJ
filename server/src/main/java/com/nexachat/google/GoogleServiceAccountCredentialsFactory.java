package com.nexachat.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads one service-account key for local FCM signing and Firebase Admin use. */
public final class GoogleServiceAccountCredentialsFactory {

    private GoogleServiceAccountCredentialsFactory() {
    }

    public static GoogleServiceAccountCredentials load(String configured, ObjectMapper objectMapper) {
        String json = readConfigured(configured, objectMapper);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("Google service account JSON is malformed");
        }
        if (root == null || !root.isObject() || !"service_account".equals(text(root, "type"))) {
            throw new IllegalStateException("Google service account JSON is malformed");
        }

        String clientEmail = requiredText(root, "client_email");
        String tokenUri = requiredText(root, "token_uri");
        requiredText(root, "private_key");
        String projectId = optionalText(root, "project_id");

        try {
            GoogleCredentials parsed = GoogleCredentials.fromStream(
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            if (!(parsed instanceof ServiceAccountCredentials serviceAccount)) {
                throw new IllegalStateException("Google credentials are not a service account key");
            }
            ServiceAccountCredentials boundedRefresh = serviceAccount.toBuilder()
                    .setDefaultRetriesEnabled(false)
                    .build();
            return new GoogleServiceAccountCredentials(
                    clientEmail,
                    projectId,
                    tokenUri,
                    boundedRefresh.getPrivateKey(),
                    boundedRefresh);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Google service account credentials are invalid");
        }
    }

    private static String readConfigured(String configured, ObjectMapper objectMapper) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Google service-account-json is empty");
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
                        "Google service-account file cannot be read: " + displayName(pathValue));
            }
        }
        return value;
    }

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
                // Not a quoted JSON document; try it as a path or malformed JSON below.
            }
        }
        return null;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String requiredText(JsonNode root, String field) {
        String value = text(root, field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Google service account JSON is missing required field '" + field + "'");
        }
        return value;
    }

    private static String optionalText(JsonNode root, String field) {
        String value = text(root, field);
        return value == null || value.isBlank() ? null : value;
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

    private static boolean looksLikePath(String value) {
        return value.endsWith(".json") || value.contains("/") || value.contains("\\");
    }

    private static String displayName(String pathValue) {
        try {
            Path fileName = Path.of(pathValue).getFileName();
            if (fileName != null && !fileName.toString().isBlank()) {
                return fileName.toString();
            }
        } catch (RuntimeException ignored) {
            // Do not reveal a configured credential path in an error message.
        }
        return "configured service-account file";
    }
}
