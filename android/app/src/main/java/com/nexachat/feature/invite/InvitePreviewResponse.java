package com.nexachat.feature.invite;

import androidx.annotation.Nullable;

/** Safe group preview; the API deliberately omits its member roster. */
public final class InvitePreviewResponse {
    public String conversationId;
    public String title;
    @Nullable public String avatarUrl;
    public int memberCount;
    public boolean requiresApproval;
    public boolean isMember;

    public InvitePreviewResponse() { }
}
