package com.nexachat.archive.dto;

import com.nexachat.archive.ArchiveItemKind;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the storage browser. Exactly one of {@code attachment} and
 * {@code link} is non-null, selected by {@code kind}; the other is serialized
 * as an explicit JSON {@code null} so clients can branch without guessing.
 */
public record ArchiveItem(
        UUID messageId,
        long seq,
        UUID senderId,
        Instant createdAt,
        ArchiveItemKind kind,
        ArchiveAttachmentView attachment,
        ArchiveLinkView link
) {
}
