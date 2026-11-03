package com.nexachat.message.dto;

import java.util.List;

/**
 * Cursor page for {@code GET /conversations/{id}/messages} (plan §10.3).
 * {@code items} arrive newest-first (seq DESC); the client reverses for
 * display. {@code nextBeforeSeq} is the seq of the oldest item in this page —
 * echo it as {@code ?beforeSeq=} for the next, strictly-older page; it is
 * {@code null} when the page is empty. {@code hasMore} is false on the last
 * page.
 */
public record MessagePageResponse(
        List<MessageResponse> items,
        Long nextBeforeSeq,
        boolean hasMore
) {
}
