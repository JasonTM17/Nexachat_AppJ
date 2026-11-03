package com.nexachat.message.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of {@code GET /conversations/{id}/pins}: pin metadata plus the
 * pinned message in the canonical {@link MessageResponse} shape so the
 * PinnedBanner renders with one parser (tap-to-jump keys off
 * {@code message.id}).
 */
public record PinnedMessageView(UUID messageId,
                                UUID pinnedBy,
                                Instant pinnedAt,
                                MessageResponse message) {
}
