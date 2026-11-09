package com.nexachat.invite.dto;

/**
 * Request payload for patching an invite link (e.g. toggling requiresApproval).
 */
public record PatchInviteLinkRequest(
        Boolean requiresApproval
) {
}
