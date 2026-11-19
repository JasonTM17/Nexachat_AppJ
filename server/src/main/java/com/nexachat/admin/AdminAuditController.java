package com.nexachat.admin;

import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Read-only, filtered view over the append-only admin audit log. */
@RestController
@Validated
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditController {

    private final AdminAuditService auditService;

    public AdminAuditController(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public AdminAuditService.Page<AdminAuditService.Entry> list(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @Size(max = 50) String action,
            @RequestParam(required = false) @Size(max = 50) String targetType,
            @RequestParam(required = false) @Size(max = 64) String targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        AdminGuard.requireStaff();
        int safeSize = size <= 0 ? 25 : Math.min(size, 100);
        int safePage = Math.max(0, page);
        return auditService.search(actorId, action, targetType, targetId, from, to, safePage, safeSize);
    }
}
