package com.nexachat.archive.dto;

import java.util.List;

/**
 * A newest-first archive page. {@code nextCursor} is null on the last page;
 * otherwise the client echoes it verbatim as {@code ?cursor=}.
 */
public record ArchiveResponse(
        List<ArchiveItem> items,
        String nextCursor
) {
}
