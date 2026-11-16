package com.nexachat.user;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.dto.BlockStateResponse;
import com.nexachat.user.dto.BlockedPageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * User-block REST endpoints (V38 safety):
 *
 * <ul>
 *   <li>{@code POST /users/{id}/block} → 204 — idempotent; self-block is a
 *       400, unknown/deleted target a 404.</li>
 *   <li>{@code DELETE /users/{id}/block} → 204 — idempotent.</li>
 *   <li>{@code GET /users/{id}/block} → 200 {@code {"blocked": bool}} —
 *       per-peer probe for the chat composer state.</li>
 *   <li>{@code GET /users/blocked?limit=&cursor=} → 200
 *       {@link BlockedPageResponse}, newest block first.</li>
 * </ul>
 *
 * <p>No response ever tells the BLOCKED user anything: these routes only
 * expose the caller's own block edges.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserBlockController {

    private final UserBlockService blockService;

    public UserBlockController(UserBlockService blockService) {
        this.blockService = blockService;
    }

    @PostMapping("/{id}/block")
    public ResponseEntity<Void> block(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable("id") UUID targetId) {
        blockService.block(requireUserId(principal), targetId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/block")
    public ResponseEntity<Void> unblock(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable("id") UUID targetId) {
        blockService.unblock(requireUserId(principal), targetId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/block")
    public BlockStateResponse blockState(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable("id") UUID targetId) {
        return new BlockStateResponse(
                blockService.isBlocked(requireUserId(principal), targetId));
    }

    @GetMapping("/blocked")
    public BlockedPageResponse listBlocked(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return blockService.listBlocked(requireUserId(principal), limit, cursor);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
