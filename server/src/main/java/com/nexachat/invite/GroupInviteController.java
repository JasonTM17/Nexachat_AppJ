package com.nexachat.invite;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.invite.dto.CreateInviteLinkRequest;
import com.nexachat.invite.dto.InviteLinkResponse;
import com.nexachat.invite.dto.JoinRequestResponse;
import com.nexachat.invite.dto.PatchInviteLinkRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin management endpoints for group invite links and join requests.
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}")
public class GroupInviteController {

    private final InviteService inviteService;

    public GroupInviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @PostMapping("/invite-link")
    public ResponseEntity<InviteLinkResponse> createOrRotateInviteLink(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestBody(required = false) CreateInviteLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(inviteService.createOrRotateInviteLink(requireUserId(principal), conversationId, request));
    }

    @GetMapping("/invite-link")
    public InviteLinkResponse getActiveInviteLink(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        return inviteService.getActiveInviteLink(requireUserId(principal), conversationId);
    }

    @PatchMapping("/invite-link")
    public InviteLinkResponse patchInviteLink(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestBody PatchInviteLinkRequest request) {
        return inviteService.patchInviteLink(requireUserId(principal), conversationId, request);
    }

    @DeleteMapping("/invite-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInviteLink(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        inviteService.revokeInviteLink(requireUserId(principal), conversationId);
    }

    @GetMapping("/join-requests")
    public List<JoinRequestResponse> listJoinRequests(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestParam(required = false) GroupJoinRequest.Status status) {
        return inviteService.listJoinRequests(requireUserId(principal), conversationId, status);
    }

    @GetMapping("/join-requests/me")
    public JoinRequestResponse getMyJoinRequest(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        return inviteService.getMyJoinRequest(requireUserId(principal), conversationId);
    }

    @PostMapping("/join-requests/{requestId}/approve")
    public JoinRequestResponse approveJoinRequest(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID requestId) {
        return inviteService.approveJoinRequest(requireUserId(principal), conversationId, requestId);
    }

    @PostMapping("/join-requests/{requestId}/reject")
    public JoinRequestResponse rejectJoinRequest(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID requestId) {
        return inviteService.rejectJoinRequest(requireUserId(principal), conversationId, requestId);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
