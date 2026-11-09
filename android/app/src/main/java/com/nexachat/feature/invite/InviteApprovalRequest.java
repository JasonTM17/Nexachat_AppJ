package com.nexachat.feature.invite;

/** Request body for changing the active link's approval requirement. */
public final class InviteApprovalRequest {
    public final boolean requiresApproval;

    public InviteApprovalRequest(boolean requiresApproval) {
        this.requiresApproval = requiresApproval;
    }
}
