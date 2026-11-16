package com.nexachat.admin;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.config.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alert queue and analyst workflow ({@code /api/v1/admin/alerts**}). The
 * reveal endpoint is the only place plaintext content can leave the server
 * for an operator, and it is rate-limited, reason-required and audited both
 * ways.
 */
@RestController
@RequestMapping("/api/v1/admin/alerts")
public class AlertController {

    private final AlertService alertService;
    private final ClientIpResolver clientIpResolver;

    public AlertController(AlertService alertService, ClientIpResolver clientIpResolver) {
        this.alertService = alertService;
        this.clientIpResolver = clientIpResolver;
    }

    public record UpdateRequest(String status, UUID assigneeId,
                                @Size(max = 500) String reason) {
    }

    public record NoteRequest(@NotBlank @Size(max = 1000) String body) {
    }

    public record ActionRequest(@NotBlank String action,
                                @NotBlank @Size(max = 500) String reason) {
    }

    public record BulkDismissRequest(List<UUID> ids,
                                     @NotBlank @Size(max = 500) String reason) {
    }

    public record RevealRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @GetMapping
    public AdminAuditService.Page<AlertService.AlertSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        AdminGuard.requireStaff();
        return alertService.list(status, severity, category, from, to, page, size);
    }

    @GetMapping("/{alertId}")
    public AlertService.AlertDetail detail(@PathVariable UUID alertId) {
        AdminGuard.requireStaff();
        return alertService.detail(alertId);
    }

    @PatchMapping("/{alertId}")
    public AlertService.AlertDetail update(@PathVariable UUID alertId,
                                           @Valid @RequestBody UpdateRequest body,
                                           HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        return alertService.update(alertId, body.status(), body.assigneeId(), body.reason(),
                actor.userId(), AdminGuard.role(), clientIp(request));
    }

    @PostMapping("/{alertId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public void addNote(@PathVariable UUID alertId,
                        @Valid @RequestBody NoteRequest body,
                        HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        alertService.addNote(alertId, body.body(), actor.userId(), AdminGuard.role(),
                clientIp(request));
    }

    @PostMapping("/{alertId}/actions")
    public AlertService.AlertDetail action(@PathVariable UUID alertId,
                                           @Valid @RequestBody ActionRequest body,
                                           HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        return alertService.action(alertId, body.action(), body.reason(),
                actor.userId(), AdminGuard.role(), clientIp(request));
    }

    @PostMapping("/{alertId}/reveal")
    public AlertService.RevealResult reveal(@PathVariable UUID alertId,
                                            @Valid @RequestBody RevealRequest body,
                                            HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        return alertService.reveal(alertId, body.reason(), actor.userId(), AdminGuard.role(),
                clientIp(request));
    }

    @PostMapping("/bulk-dismiss")
    public Map<String, Integer> bulkDismiss(@Valid @RequestBody BulkDismissRequest body,
                                            HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        int dismissed = alertService.bulkDismiss(body.ids(), body.reason(),
                actor.userId(), AdminGuard.role(), clientIp(request));
        return Map.of("dismissed", dismissed);
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.rateLimitClientIp(request);
    }
}
