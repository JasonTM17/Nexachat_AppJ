package com.nexachat.message;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.message.dto.StarredPageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Starred-message REST endpoints (V38 safety), caller-scoped:
 *
 * <ul>
 *   <li>{@code PUT /messages/{id}/star} → 204 — idempotent; requires
 *       membership admitted at the message's creation.</li>
 *   <li>{@code DELETE /messages/{id}/star} → 204 — idempotent.</li>
 *   <li>{@code GET /messages/starred?limit=&cursor=} → 200
 *       {@link StarredPageResponse}, newest star first.</li>
 * </ul>
 *
 * <p>Stars are private: nothing here reaches the conversation, the sender,
 * or other members — no event, no counter, no badge.
 */
@RestController
@RequestMapping("/api/v1/messages")
public class StarController {

    private final StarService starService;

    public StarController(StarService starService) {
        this.starService = starService;
    }

    @PutMapping("/{id}/star")
    public ResponseEntity<Void> star(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable("id") UUID messageId) {
        starService.star(requireUserId(principal), messageId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/star")
    public ResponseEntity<Void> unstar(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable("id") UUID messageId) {
        starService.unstar(requireUserId(principal), messageId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/starred")
    public StarredPageResponse listStarred(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return starService.listStarred(requireUserId(principal), limit, cursor);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
