package com.nexachat.message;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.message.dto.ReactRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Reaction REST endpoints:
 *
 * <ul>
 *   <li>{@code POST /{id}/reactions {emoji}} → 204, TOGGLE semantics —
 *       members of the message's conversation only. The post-toggle state
 *       reaches clients through the {@code REACTION_UPDATED} topic frame;
 *       the 204 deliberately carries no body.</li>
 *   <li>{@code PUT /{id}/reactions/{emoji}} → 204, idempotent ADD — a replay
 *       of an outbox action never inverts state.</li>
 *   <li>{@code DELETE /{id}/reactions/{emoji}} → 204, idempotent REMOVE.</li>
 * </ul>
 *
 * <p>All three accept an optional {@code Idempotency-Key} header (a UUID).
 * A repeated key returns the original 204 without running the operation again;
 * the same key on a different endpoint or payload is
 * {@code 409 IDEMPOTENCY_KEY_REUSED}.
 */
@RestController
@RequestMapping("/api/v1/messages")
public class ReactionController {

    private final ReactionService reactionService;
    private final IdempotencyService idempotencyService;

    public ReactionController(ReactionService reactionService, IdempotencyService idempotencyService) {
        this.reactionService = reactionService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/{messageId}/reactions")
    public ResponseEntity<Void> react(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID messageId,
            @Valid @RequestBody ReactRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID userId = requireUserId(principal);
        String endpoint = "POST /api/v1/messages/" + messageId + "/reactions";
        idempotencyService.execute(userId, idempotencyKey, endpoint,
                request == null ? "" : String.valueOf(request.emoji()),
                () -> reactionService.toggle(userId, messageId, request));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{messageId}/reactions/{emoji}")
    public ResponseEntity<Void> addReaction(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID messageId,
            @PathVariable String emoji,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID userId = requireUserId(principal);
        String endpoint = "PUT /api/v1/messages/" + messageId + "/reactions/" + emoji;
        idempotencyService.execute(userId, idempotencyKey, endpoint, emoji,
                () -> reactionService.add(userId, messageId, emoji));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{messageId}/reactions/{emoji}")
    public ResponseEntity<Void> removeReaction(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID messageId,
            @PathVariable String emoji,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID userId = requireUserId(principal);
        String endpoint = "DELETE /api/v1/messages/" + messageId + "/reactions/" + emoji;
        idempotencyService.execute(userId, idempotencyKey, endpoint, emoji,
                () -> reactionService.remove(userId, messageId, emoji));
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
