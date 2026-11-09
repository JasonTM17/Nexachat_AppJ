package com.nexachat.archive.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for one attachment-bearing message on the media/file tabs.
 */
public interface ArchiveAttachmentRow {
    UUID getMessageId();

    Long getSeq();

    UUID getSenderId();

    Instant getCreatedAt();

    UUID getAttachmentId();

    String getFileName();

    String getMimeType();

    Long getSizeBytes();

    Integer getDurationMs();

    String getBlurHash();

    Integer getWidth();

    Integer getHeight();
}
