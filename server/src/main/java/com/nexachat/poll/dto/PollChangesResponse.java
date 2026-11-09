package com.nexachat.poll.dto;

import java.util.List;

/**
 * Poll change feed page ordered by {@code version} ascending. Echo
 * {@code nextSince} on the next request; {@code hasMore} means another page is
 * available immediately at that cursor.
 */
public record PollChangesResponse(
        List<PollChangeItem> items,
        long nextSince,
        boolean hasMore
) {
}
