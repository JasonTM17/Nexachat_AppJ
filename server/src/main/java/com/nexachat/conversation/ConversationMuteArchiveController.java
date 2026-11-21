package com.nexachat.conversation;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.MuteRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Per-member mute, archive and unread routes under {@code /api/v1/conversations}.
 *
 * <p>All are member-only, idempotent and return an empty {@code 204}: the
 * state itself is read back from the inbox (or delta sync), so there is no
 * response body to keep in sync. Validation is fail-closed — see
 * {@link ConversationMuteArchiveService#mute}.
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationMuteArchiveController {

    private final ConversationMuteArchiveService muteArchiveService;

    public ConversationMuteArchiveController(ConversationMuteArchiveService muteArchiveService) {
        this.muteArchiveService = muteArchiveService;
    }

    @PutMapping("/{conversationId}/mute")
    public ResponseEntity<Void> mute(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody MuteRequest request) {
        muteArchiveService.mute(requireUserId(principal), conversationId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{conversationId}/mute")
    public ResponseEntity<Void> unmute(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        muteArchiveService.unmute(requireUserId(principal), conversationId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{conversationId}/archive")
    public ResponseEntity<Void> archive(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        muteArchiveService.archive(requireUserId(principal), conversationId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{conversationId}/archive")
    public ResponseEntity<Void> unarchive(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        muteArchiveService.unarchive(requireUserId(principal), conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Caller-only unread bookmark. Empty 204; the inbox {@code unreadCount} is
     * the source of truth on the next fetch. Peers are not notified.
     */
    @PutMapping("/{conversationId}/unread")
    public ResponseEntity<Void> markUnread(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        muteArchiveService.markUnread(requireUserId(principal), conversationId);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
