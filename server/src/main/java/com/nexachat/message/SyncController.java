package com.nexachat.message;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.message.dto.SyncRequest;
import com.nexachat.message.dto.SyncResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1")
public class SyncController {

    private final MessageService messageService;

    public SyncController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping({"/sync", "/chat/sync"})
    public ResponseEntity<SyncResponse> syncDelta(
            @AuthenticationPrincipal JwtPrincipal principal,
            @Valid @RequestBody SyncRequest request) {
        UUID userId = requireUserId(principal);
        SyncResponse response = messageService.syncDelta(userId, request);
        return ResponseEntity.ok(response);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
