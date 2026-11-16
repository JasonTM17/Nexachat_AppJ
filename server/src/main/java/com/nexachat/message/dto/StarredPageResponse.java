package com.nexachat.message.dto;

import java.util.List;

/**
 * Keyset page of the viewer's starred messages, newest star first.
 * {@code nextCursor} is opaque; {@code null} ends the list.
 */
public record StarredPageResponse(List<StarredMessageView> items, String nextCursor) {
}
