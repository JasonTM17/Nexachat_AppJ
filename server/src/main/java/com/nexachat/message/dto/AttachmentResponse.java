package com.nexachat.message.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * One attachment as the client renders it: a URL to stream plus display
 * metadata. {@code url} resolves via {@code StorageService.resolveUrl} so the
 * storage key stays an internal detail.
 *
 * <p>{@code blurHash} is the optional BlurHash placeholder for image
 * attachments; it is {@code null} (and omitted on the wire) for non-image and
 * legacy rows.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentResponse(
        UUID id,
        String url,
        String originalName,
        String mimeType,
        long sizeBytes,
        Integer width,
        Integer height,
        Integer durationMs,
        String blurHash
) {
}
