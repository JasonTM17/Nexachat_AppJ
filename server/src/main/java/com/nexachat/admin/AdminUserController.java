package com.nexachat.admin;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.config.ClientIpResolver;
import com.nexachat.user.User;
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
 * Account administration REST surface ({@code /api/v1/admin/users**}).
 * Role gate ({@code ADMIN|MODERATOR}) is applied by the security filter chain;
 * per-action splits are enforced inside {@link AdminUserService}.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService service;
    private final ClientIpResolver clientIpResolver;

    public AdminUserController(AdminUserService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RoleRequest(@NotBlank String role, @NotBlank @Size(max = 500) String reason) {
    }

    public record WarnRequest(@NotBlank @Size(max = 500) String reason, UUID alertId) {
    }

    @GetMapping
    public AdminAuditService.Page<AdminUserService.UserSummary> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        AdminGuard.requireStaff();
        return service.list(search, status, role, createdFrom, createdTo, page, size);
    }

    /** Literal route wins over {@code /{userId}} — scheduled-deletion view. */
    @GetMapping("/deleted")
    public List<AdminUserService.UserSummary> deleted(@RequestParam(defaultValue = "50") int limit) {
        AdminGuard.requireStaff();
        return service.deletedAccounts(limit);
    }

    @GetMapping("/{userId}")
    public AdminUserService.UserDetail detail(@PathVariable UUID userId) {
        AdminGuard.requireStaff();
        return service.detail(userId);
    }

    @PostMapping("/{userId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(@PathVariable UUID userId,
                        @Valid @RequestBody ReasonRequest body,
                        HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        service.suspend(userId, body.reason(), actor.userId(), AdminGuard.role(), clientIp(request));
    }

    @PostMapping("/{userId}/unsuspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsuspend(@PathVariable UUID userId,
                          @RequestBody(required = false) ReasonRequest body,
                          HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        service.unsuspend(userId, body == null ? null : body.reason(),
                actor.userId(), AdminGuard.role(), clientIp(request));
    }

    @PostMapping("/{userId}/revoke-sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSessions(@PathVariable UUID userId,
                               @RequestBody(required = false) ReasonRequest body,
                               HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        service.revokeSessions(userId, body == null ? null : body.reason(),
                actor.userId(), AdminGuard.role(), clientIp(request));
    }

    @PatchMapping("/{userId}/role")
    public AdminUserService.UserDetail changeRole(@PathVariable UUID userId,
                                                @Valid @RequestBody RoleRequest body,
                                                HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        service.changeRole(userId, parseRole(body.role()), body.reason(),
                actor.userId(), AdminGuard.role(), clientIp(request));
        return service.detail(userId);
    }

    @PostMapping("/{userId}/actions")
    public AdminUserService.NoticeResult warnUser(@PathVariable UUID userId,
                                                  @Valid @RequestBody WarnRequest body,
                                                  HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        return service.warnUser(userId, body.alertId(), body.reason(),
                actor.userId(), AdminGuard.role(), clientIp(request));
    }

    private static User.Role parseRole(String raw) {
        try {
            return User.Role.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw com.nexachat.common.error.ApiException.of(
                    com.nexachat.common.error.ApiErrorCode.VALIDATION_FAILED, "Unknown role");
        }
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.rateLimitClientIp(request);
    }
}
