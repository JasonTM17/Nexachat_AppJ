package com.nexachat.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Stable SHA-256 hashes for content and question keys. */
public final class RagHashes {

    private RagHashes() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Hash of the exact text that was embedded. External providers receive
     * redacted text, so the hash is computed over the redacted input.
     */
    public static String embeddingInputHash(String embeddedText) {
        return sha256("embedding:" + embeddedText);
    }

    /** Hash of a stored message's original content, used to validate cached citations. */
    public static String messageContentHash(UUID messageId, Object editedAt, String content) {
        return sha256("message:" + messageId + ":" + (editedAt == null ? "" : editedAt.toString())
                + ":" + (content == null ? "" : content));
    }

    public static String questionHash(String normalizedQuestion) {
        return sha256("question:" + normalizedQuestion);
    }

    /** Combined hash over cited message hashes, used to validate a cached answer. */
    public static String citationsHash(java.util.List<RagMessage> messages) {
        Map<UUID, String> hashes = new java.util.HashMap<>();
        for (RagMessage message : messages) {
            hashes.put(message.id(), messageContentHash(message.id(), message.editedAt(), message.content()));
        }
        return combineHashes(hashes);
    }

    public static String combineHashes(Map<UUID, String> hashes) {
        String joined = hashes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("|"));
        return sha256("citations:" + joined);
    }
}
