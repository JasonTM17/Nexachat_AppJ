package com.nexachat.feature.invite;

import androidx.annotation.Nullable;

/** Active-link metadata; the server returns the raw token only after create or rotate. */
public final class InviteLinkResponse {
    public String id;
    public String conversationId;
    @Nullable public String token;
    public boolean requiresApproval;
    @Nullable public String expiresAt;
    @Nullable public Integer maxUses;
    public int useCount;
    @Nullable public String createdAt;

    public InviteLinkResponse() { }

    @Override
    public String toString() {
        return "InviteLinkResponse{conversationId=" + conversationId + ", token=[redacted]}";
    }
}
