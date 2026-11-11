package com.nexachat.firebase.auth;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.firebase.config.FirebaseRealtimeAvailableCondition;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Firebase bridge endpoints are absent unless the server explicitly opts in. */
@RestController
@RequestMapping("/api/v1/realtime")
@Conditional(FirebaseRealtimeAvailableCondition.class)
public final class FirebaseRealtimeController {

    private final FirebaseRealtimeAuthService service;

    public FirebaseRealtimeController(FirebaseRealtimeAuthService service) {
        this.service = service;
    }

    @PostMapping("/firebase-token")
    public ResponseEntity<FirebaseTokenResponse> issue(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestBody(required = false) Void emptyBody) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.issue(principal));
    }

    @PostMapping("/firebase-lease")
    public ResponseEntity<FirebaseLeaseResponse> renew(
            @AuthenticationPrincipal JwtPrincipal principal,
            @Valid @RequestBody FirebaseLeaseRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.renew(principal, request));
    }

    @DeleteMapping("/firebase-session/{bridgeId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal JwtPrincipal principal,
                                       @PathVariable UUID bridgeId) {
        service.revokeBridge(principal, bridgeId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
