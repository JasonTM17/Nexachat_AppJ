package com.nexachat.message;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.message.dto.MessagePageResponse;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.message.dto.EditMessageRequest;
import com.nexachat.message.dto.ReadRequest;
import com.nexachat.message.dto.SendMessageRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Message REST endpoints (plan §10.2):
 *
 * <ul>
 *   <li>{@code GET /{id}/messages?beforeSeq=&limit=} — cursor history,
 *       members only ({@code NOT_MEMBER}/{@code CONVERSATION_NOT_FOUND}).</li>
 *   <li>{@code POST /{id}/messages} — REST send fallback for when the socket
 *       is down; the same {@link MessageService#send} as the WS path. 201 on
 *       create, 200 with the already-stored row on a dedup hit (idempotent —
 *       §10.3 "gửi lại đúng request → trả message cũ").</li>
 *   <li>{@code POST /{id}/read {upToSeq}} — watermark advance; identical
 *       semantics to the STOMP {@code .../read} frame (shared service
 *       method), including the {@code MESSAGE_READ} broadcast.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/messages")
    public MessagePageResponse history(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(required = false) Integer limit) {
        return messageService.getHistory(
                requireUserId(principal), conversationId, beforeSeq, afterSeq, limit);
    }

    @PostMapping("/messages")
    public ResponseEntity<MessageResponse> send(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request) {
        MessageService.SendResult result =
                messageService.send(requireUserId(principal), conversationId, request);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.message());
    }

    @PatchMapping("/messages/{messageId}")
    public MessageResponse edit(@AuthenticationPrincipal JwtPrincipal principal,
                                @PathVariable UUID conversationId,
                                @PathVariable UUID messageId,
                                @Valid @RequestBody EditMessageRequest request) {
        return messageService.edit(requireUserId(principal), conversationId, messageId, request.content());
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal JwtPrincipal principal,
                                       @PathVariable UUID conversationId,
                                       @PathVariable UUID messageId) {
        messageService.delete(requireUserId(principal), conversationId, messageId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/messages/search")
    public MessagePageResponse search(@AuthenticationPrincipal JwtPrincipal principal,
                                      @PathVariable UUID conversationId,
                                      @RequestParam String q,
                                      @RequestParam(required = false) Integer limit,
                                      @RequestParam(required = false) Long beforeSeq) {
        return messageService.search(requireUserId(principal), conversationId, q, limit, beforeSeq);
    }

    @PostMapping("/read")
    public ResponseEntity<Void> markRead(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody ReadRequest request) {
        messageService.markRead(requireUserId(principal), conversationId, request);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
