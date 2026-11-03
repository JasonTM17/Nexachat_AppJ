package com.nexachat.message.dto;

import java.util.UUID;

/**
 * One attachment as the client renders it: a URL to stream plus display
 * metadata. {@code url} resolves via {@code StorageService.resolveUrl} so the
 * storage key stays an internal detail.
 */
public record AttachmentResponse(
        UUID id,
        String url,
        String originalName,
        String mimeType,
        long sizeBytes,
        Integer width,
        Integer height,
        Integer durationMs
) {
}
