package com.nexachat.feature.invite;

import androidx.annotation.Nullable;

/** Create/rotate options. A null expiry or use cap means unlimited. */
public final class InviteLinkRequest {
    @Nullable public final String expiresAt;
    @Nullable public final Integer maxUses;
    public final boolean requiresApproval;

    public InviteLinkRequest(
            @Nullable String expiresAt,
            @Nullable Integer maxUses,
            boolean requiresApproval) {
        this.expiresAt = expiresAt;
        this.maxUses = maxUses;
        this.requiresApproval = requiresApproval;
    }
}
