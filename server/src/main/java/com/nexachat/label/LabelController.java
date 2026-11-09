package com.nexachat.label;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** REST endpoints for caller-private labels. */
@RestController
@RequestMapping("/api/v1/labels")
public class LabelController {

    private final LabelService labelService;

    public LabelController(LabelService labelService) {
        this.labelService = labelService;
    }

    @GetMapping
    public List<LabelResponse> list(@AuthenticationPrincipal JwtPrincipal principal) {
        return labelService.list(requireUserId(principal));
    }

    @PostMapping
    public ResponseEntity<LabelResponse> create(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestBody(required = false) CreateLabelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(labelService.create(requireUserId(principal), request));
    }

    @PatchMapping("/{labelId}")
    public LabelResponse patch(@AuthenticationPrincipal JwtPrincipal principal,
                               @PathVariable UUID labelId,
                               @RequestBody(required = false) PatchLabelRequest request) {
        return labelService.patch(requireUserId(principal), labelId, request);
    }

    @DeleteMapping("/{labelId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal JwtPrincipal principal,
                                       @PathVariable UUID labelId) {
        labelService.delete(requireUserId(principal), labelId);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
