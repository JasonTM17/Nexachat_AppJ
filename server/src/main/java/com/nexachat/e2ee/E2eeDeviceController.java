package com.nexachat.e2ee;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.e2ee.dto.E2eeDeviceResponse;
import com.nexachat.e2ee.dto.RegisterE2eeDeviceRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Device identity keys for E2EE: clients PUT their X25519 public key once per
 * installation and DELETE it when the installation is retired. Peers fetch
 * member devices through {@code GET /conversations/{id}/e2ee}.
 */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/e2ee/devices")
public class E2eeDeviceController {

    private final E2eeDeviceService deviceService;

    public E2eeDeviceController(E2eeDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PutMapping("/{deviceId}")
    public E2eeDeviceResponse register(@AuthenticationPrincipal JwtPrincipal principal,
                                       @PathVariable UUID deviceId,
                                       @Valid @RequestBody RegisterE2eeDeviceRequest request) {
        return deviceService.register(requireUserId(principal), deviceId, request.publicKey());
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal JwtPrincipal principal,
                                       @PathVariable UUID deviceId) {
        deviceService.revoke(requireUserId(principal), deviceId);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
