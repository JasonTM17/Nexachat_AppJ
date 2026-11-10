package com.nexachat.conversation;

import com.nexachat.attachment.AvatarController.AvatarResponse;
import com.nexachat.common.MultipartRequests;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.AddMemberRequest;
import com.nexachat.conversation.dto.ConversationResponse;
import com.nexachat.conversation.dto.ConversationUpdatePayload;
import com.nexachat.conversation.dto.CreateGroupRequest;
import com.nexachat.conversation.dto.PatchGroupRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * GROUP endpoints (plan §10.2, phase-08) — same
 * {@code /api/v1/conversations} base as {@link ConversationController}:
 *
 * <ul>
 *   <li>{@code POST /group} → 201 {@link ConversationResponse} (creator=ADMIN,
 *       listed members=MEMBER, one transaction).</li>
 *   <li>{@code POST /{id}/members} → 204, ADMIN only, idempotent.</li>
 *   <li>{@code DELETE /{id}/members/{userId}} → 204, ADMIN — or any member
 *       when {@code userId} is the caller (self-leave, per plan).</li>
 *   <li>{@code POST /{id}/leave} → 204, any member; the dedicated self-leave
 *       path the task contract defines.</li>
 *   <li>{@code PATCH /{id}} → 200 {@link ConversationUpdatePayload}, ADMIN
 *       only (title/avatar).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping("/group")
    public ResponseEntity<ConversationResponse> createGroup(
            @AuthenticationPrincipal JwtPrincipal principal,
            @Valid @RequestBody CreateGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(groupService.createGroup(requireUserId(principal), request));
    }

    @PostMapping("/{conversationId}/members")
    public ResponseEntity<Void> addMembers(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody AddMemberRequest request) {
        groupService.addMembers(requireUserId(principal), conversationId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{conversationId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID userId) {
        groupService.removeMember(requireUserId(principal), conversationId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{conversationId}/leave")
    public ResponseEntity<Void> leave(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        groupService.leave(requireUserId(principal), conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code POST /{id}/avatar} — multipart image upload, ADMIN only. The
     * stored file is served from {@code /api/v1/files/**} and the new URL is
     * broadcast via {@code CONVERSATION_UPDATED}.
     */
    @PostMapping("/{conversationId}/avatar")
    public AvatarResponse setAvatar(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestParam("file") List<MultipartFile> files) throws IOException {
        return groupService.setGroupAvatar(requireUserId(principal), conversationId,
                MultipartRequests.requireSingleFile(files));
    }

    @PatchMapping("/{conversationId}")
    public ConversationUpdatePayload updateGroup(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody PatchGroupRequest request) {
        return groupService.updateGroup(requireUserId(principal), conversationId, request);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
