package com.nexachat.attachment;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Group avatars are loaded by every member's client. Only a relative
 * {@code /api/v1/files/} path is stored, so a URL cannot send those devices
 * to another host. An absolute URL whose path is already under that prefix
 * is rewritten to the relative form; anything else is rejected.
 */
public final class AvatarUrlPolicy {

    static final String FILES_PATH = "/api/v1/files/";

    /**
     * Key shape produced by the server for user and group avatars
     * ({@code avatars/<uuid>.<sniffed-extension>}). The sweep only ever
     * considers files matching this shape, so hand-placed files stay put.
     */
    static final Pattern AVATAR_STORAGE_KEY = Pattern.compile(
            "^avatars/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.[a-z0-9]+$");

    private static final int MAX_LENGTH = 255;

    private AvatarUrlPolicy() {
    }

    public static String toRelativeFilesUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid();
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LENGTH || hasControlChars(trimmed)) {
            throw invalid();
        }
        final URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
        String scheme = uri.getScheme();
        if (scheme != null
                && !scheme.equalsIgnoreCase("https")
                && !scheme.equalsIgnoreCase("http")) {
            throw invalid();
        }
        if (uri.getRawUserInfo() != null || uri.isOpaque()) {
            throw invalid();
        }
        String path = uri.getPath();
        if (path == null
                || !path.startsWith(FILES_PATH)
                || path.contains("..")
                || path.contains("\\")
                || path.contains("//")) {
            throw invalid();
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.startsWith(FILES_PATH) || rawPath.contains("..")) {
            throw invalid();
        }
        String query = uri.getRawQuery();
        String relative = query == null || query.isEmpty() ? rawPath : rawPath + "?" + query;
        if (relative.length() > MAX_LENGTH || hasControlChars(relative)) {
            throw invalid();
        }
        return relative;
    }

    private static boolean hasControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static ApiException invalid() {
        return ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                "avatarUrl must be a relative /api/v1/files/ path");
    }
}
