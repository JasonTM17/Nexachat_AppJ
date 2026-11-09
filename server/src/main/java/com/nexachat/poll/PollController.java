package com.nexachat.poll;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.poll.dto.CreatePollRequest;
import com.nexachat.poll.dto.PollChangesResponse;
import com.nexachat.poll.dto.PollPageResponse;
import com.nexachat.poll.dto.PollResponse;
import com.nexachat.poll.dto.VoteRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Group-poll REST endpoints:
 *
 * <ul>
 *   <li>{@code POST /polls} → 201 full poll state.</li>
 *   <li>{@code GET /polls?limit=&before=} → newest-first page.</li>
 *   <li>{@code GET /polls/changes?since=&limit=} → version feed (no STOMP
 *       event exists for polls).</li>
 *   <li>{@code GET /polls/{pollId}} → full poll state.</li>
 *   <li>{@code PUT /polls/{pollId}/vote} → replaces the caller's selection,
 *       200 full state (safe to retry).</li>
 *   <li>{@code DELETE /polls/{pollId}/vote} → retracts, 200 full state.</li>
 *   <li>{@code POST /polls/{pollId}/close} → creator/ADMIN, idempotent.</li>
 *   <li>{@code DELETE /polls/{pollId}} → creator/ADMIN, idempotent 204.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/polls")
public class PollController {

    private final PollService pollService;

    public PollController(PollService pollService) {
        this.pollService = pollService;
    }

    @PostMapping
    public ResponseEntity<PollResponse> create(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody CreatePollRequest request) {
        PollResponse response = pollService.create(requireUserId(principal), conversationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public PollPageResponse list(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) UUID before) {
        return pollService.list(requireUserId(principal), conversationId, limit, before);
    }

    @GetMapping("/changes")
    public PollChangesResponse changes(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "100") int limit) {
        return pollService.changes(requireUserId(principal), conversationId, since, limit);
    }

    @GetMapping("/{pollId}")
    public PollResponse get(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID pollId) {
        return pollService.get(requireUserId(principal), conversationId, pollId);
    }

    @PutMapping("/{pollId}/vote")
    public PollResponse vote(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID pollId,
            @Valid @RequestBody VoteRequest request) {
        return pollService.vote(requireUserId(principal), conversationId, pollId, request);
    }

    @DeleteMapping("/{pollId}/vote")
    public PollResponse retractVote(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID pollId) {
        return pollService.retractVote(requireUserId(principal), conversationId, pollId);
    }

    @PostMapping("/{pollId}/close")
    public PollResponse close(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID pollId) {
        return pollService.close(requireUserId(principal), conversationId, pollId);
    }

    @DeleteMapping("/{pollId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID pollId) {
        pollService.delete(requireUserId(principal), conversationId, pollId);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
