package com.nexachat.archive.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for one TEXT message that may contain links. URL extraction
 * stays in Java ({@link com.nexachat.archive.ArchiveLinkExtractor}); this row
 * carries only the raw content once per message.
 */
public interface ArchiveLinkRow {
    UUID getMessageId();

    Long getSeq();

    UUID getSenderId();

    Instant getCreatedAt();

    String getContent();
}
