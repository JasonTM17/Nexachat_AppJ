package com.nexachat.call;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Validated
@RequestMapping("/api/v1")
public class CallRestController {

    private final CallService callService;

    public CallRestController(CallService callService) {
        this.callService = callService;
    }

    @GetMapping("/calls/ice-config")
    public CallIceConfig iceConfig(@AuthenticationPrincipal JwtPrincipal principal) {
        requireUserId(principal);
        return callService.iceConfig();
    }

    @GetMapping("/conversations/{id}/calls")
    public CallHistoryPage history(@AuthenticationPrincipal JwtPrincipal principal,
                                   @PathVariable("id") UUID conversationId,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                   @RequestParam(required = false) String cursor) {
        return callService.history(requireUserId(principal), conversationId, limit, cursor);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
