package com.nexachat.archive.dto;

/**
 * One message attachment as an archive item: metadata only, no storage key and
 * no download URL (opening the file reuses the message/attachment APIs).
 */
public record ArchiveAttachmentView(
        java.util.UUID id,
        String fileName,
        String mimeType,
        long sizeBytes,
        Integer durationMs,
        String blurHash,
        Integer width,
        Integer height
) {
}
