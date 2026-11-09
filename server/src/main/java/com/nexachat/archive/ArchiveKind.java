package com.nexachat.archive;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

import java.util.Locale;

/**
 * The three storage-browser tabs. {@code MEDIA} expands to the item kinds
 * {@code IMAGE}/{@code VIDEO}, {@code FILE} to {@code FILE} and {@code LINK}
 * to {@code LINK}; the request contract keeps one tab per call.
 */
public enum ArchiveKind {
    MEDIA,
    FILE,
    LINK;

    /**
     * Parses the {@code type} query parameter. The contract names the values in
     * upper case; surrounding whitespace is tolerated, an unknown value is a
     * client error.
     *
     * @throws ApiException {@code VALIDATION_FAILED} for a missing/unknown type
     */
    public static ArchiveKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "type is required and must be MEDIA, FILE or LINK");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "type must be MEDIA, FILE or LINK");
        }
    }
}
