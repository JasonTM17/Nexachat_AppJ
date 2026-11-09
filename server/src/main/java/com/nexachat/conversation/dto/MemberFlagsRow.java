package com.nexachat.conversation.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The caller's own mute/archive flags for one conversation, carried by delta
 * sync so a preference changed on one device reaches every other device.
 *
 * <p>{@code muted} is the raw stored flag; {@link #isMutedAt(Instant)} applies
 * the lazy expiry so a mute whose instant has passed is reported as not muted.
 * {@code mutedUntil} is {@code null} for a forever mute.
 */
public record MemberFlagsRow(
        UUID conversationId,
        boolean muted,
        Instant mutedUntil,
        boolean archived
) {

    /** Effective mute state at {@code now}: a past expiry no longer mutes. */
    public boolean isMutedAt(Instant now) {
        return muted && (mutedUntil == null || mutedUntil.isAfter(now));
    }

    /** The expiry to publish, or {@code null} when the mute is not effective. */
    public Instant effectiveMutedUntil(Instant now) {
        return isMutedAt(now) ? mutedUntil : null;
    }
}
