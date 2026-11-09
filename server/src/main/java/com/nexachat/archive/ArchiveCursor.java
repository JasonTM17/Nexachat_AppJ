package com.nexachat.archive;

import java.util.UUID;

/**
 * Decoded archive keyset position.
 *
 * <p>{@code sub} is the item identity inside one message: the attachment id for
 * {@link ArchiveKind#MEDIA}/{@link ArchiveKind#FILE}, or the link offset for
 * {@link ArchiveKind#LINK} where {@code -1} means "continue strictly below
 * {@code seq}" (the whole message was consumed).
 */
public record ArchiveCursor(
        UUID conversationId,
        ArchiveKind kind,
        long seq,
        String sub
) {
}
