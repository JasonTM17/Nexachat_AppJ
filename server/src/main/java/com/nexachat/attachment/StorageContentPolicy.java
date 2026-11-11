package com.nexachat.attachment;

import com.nexachat.common.MagicBytes;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Shared key and content checks for every {@link StorageService} backend. */
final class StorageContentPolicy {

    private static final Map<String, String> MIME_TO_EXTENSION = Map.ofEntries(
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/png", ".png"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/gif", ".gif"),
            Map.entry("video/mp4", ".mp4"),
            Map.entry("audio/mpeg", ".mp3"),
            Map.entry("audio/mp4", ".m4a"),
            Map.entry("audio/ogg", ".ogg"),
            Map.entry("application/pdf", ".pdf"),
            Map.entry("text/plain", ".txt"),
            Map.entry("text/csv", ".csv"),
            Map.entry("text/markdown", ".md"),
            Map.entry("application/zip", ".zip"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx")
    );

    private static final Set<String> TEXT_FALLBACK_MIMES =
            Set.of("text/plain", "text/markdown", "text/csv");

    private StorageContentPolicy() {
    }

    static String sniffMime(String contentTypeDeclared, byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        String sniffed = MagicBytes.sniff(bytes);
        if (sniffed == null) {
            sniffed = sniffTextContent(contentTypeDeclared, bytes);
        }
        return sniffed != null && MagicBytes.ALLOWED_MIMES.contains(sniffed) ? sniffed : null;
    }

    static String safeSubdir(String subdir) {
        if (subdir == null || subdir.isBlank()
                || subdir.contains("..")
                || subdir.contains("/")
                || subdir.contains("\\")
                || !subdir.matches("[A-Za-z0-9._-]+")) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Invalid storage subdirectory");
        }
        return subdir;
    }

    static boolean isSafeStorageKey(String storageKey) {
        return storageKey != null && !storageKey.isBlank()
                && !storageKey.contains("..")
                && !storageKey.contains("\\");
    }

    static boolean isCanonicalStorageKey(String storageKey) {
        if (!isSafeStorageKey(storageKey) || storageKey.startsWith("/")) {
            return false;
        }
        for (String segment : storageKey.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    static String extensionFor(String mimeType) {
        return MIME_TO_EXTENSION.getOrDefault(mimeType, ".bin");
    }

    private static String sniffTextContent(String contentTypeDeclared, byte[] bytes) {
        if (contentTypeDeclared == null || !TEXT_FALLBACK_MIMES.contains(contentTypeDeclared)) {
            return null;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException ex) {
            return null;
        }
        if (looksLikeActiveMarkup(bytes)) {
            return null;
        }
        return contentTypeDeclared;
    }

    private static boolean looksLikeActiveMarkup(byte[] bytes) {
        int start = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        while (start < bytes.length) {
            byte current = bytes[start];
            if (current != ' ' && current != '\n' && current != '\r' && current != '\t') {
                break;
            }
            start++;
        }
        int length = Math.min(bytes.length - start, 16);
        if (length <= 0) {
            return false;
        }
        String head = new String(bytes, start, length, StandardCharsets.UTF_8)
                .toLowerCase(java.util.Locale.ROOT);
        return head.startsWith("<!doctype")
                || head.startsWith("<html")
                || head.startsWith("<svg")
                || head.startsWith("<script")
                || head.startsWith("<xhtml")
                || head.startsWith("<?xml");
    }
}
