package com.nexachat.notification;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.notification.dto.DeviceTokenRequest;
import com.nexachat.notification.dto.DeviceTokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1")
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    public DeviceTokenController(DeviceTokenService deviceTokenService) {
        this.deviceTokenService = deviceTokenService;
    }

    @PostMapping({"/users/device-token", "/devices/token"})
    public ResponseEntity<DeviceTokenResponse> registerToken(
            @AuthenticationPrincipal JwtPrincipal principal,
            @Valid @RequestBody DeviceTokenRequest request) {
        UUID userId = requireUserId(principal);
        DeviceTokenResponse response = deviceTokenService.registerToken(userId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping({"/users/device-token", "/devices/token"})
    public ResponseEntity<Void> unregisterToken(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(value = "token", required = false) String paramToken,
            @RequestBody(required = false) DeviceTokenRequest bodyRequest) {
        UUID userId = requireUserId(principal);
        String token = paramToken != null && !paramToken.isBlank()
                ? paramToken
                : (bodyRequest != null ? bodyRequest.token() : null);
        if (token == null || token.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "token parameter or body is required");
        }
        deviceTokenService.unregisterToken(userId, token);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
