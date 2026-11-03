package com.nexachat.message;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.message.dto.PinRequest;
import com.nexachat.message.dto.PinnedMessageView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Pinned-message REST endpoints (phase-10, plan §10.2):
 *
 * <ul>
 *   <li>{@code GET /pins} → 200 {@link PinnedMessageView} list, oldest pin
 *       first — member only.</li>
 *   <li>{@code POST /pins {messageId}} → 204 — DIRECT: any member; GROUP:
 *       ADMIN only; ≤ {@code PinService#MAX_PINS_PER_CONVERSATION} pins per
 *       conversation (409 {@code PIN_LIMIT}). Idempotent on repeat pin.</li>
 *   <li>{@code DELETE /pins/{messageId}} → 204 — same authZ; idempotent on
 *       not-pinned.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/pins")
public class PinController {

    private final PinService pinService;

    public PinController(PinService pinService) {
        this.pinService = pinService;
    }

    @GetMapping
    public List<PinnedMessageView> listPins(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId) {
        return pinService.listPins(requireUserId(principal), conversationId);
    }

    @PostMapping
    public ResponseEntity<Void> pin(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody PinRequest request) {
        pinService.pin(requireUserId(principal), conversationId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> unpin(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId) {
        pinService.unpin(requireUserId(principal), conversationId, messageId);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
