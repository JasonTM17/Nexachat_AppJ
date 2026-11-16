package com.nexachat.admin;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.config.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Alert-rule administration ({@code /api/v1/admin/rules**}). */
@RestController
@RequestMapping("/api/v1/admin/rules")
public class AlertRuleController {

    private final AlertRuleService service;
    private final ClientIpResolver clientIpResolver;

    public AlertRuleController(AlertRuleService service, ClientIpResolver clientIpResolver) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
    }

    public record RuleRequest(@NotBlank @Size(max = 200) String name,
                              @NotBlank String category,
                              @NotBlank String severity,
                              @NotBlank String patternType,
                              @Size(max = 200) String pattern,
                              @Size(max = 20) String locale,
                              @Size(max = 500) String description) {
    }

    public record RulePatch(@Size(max = 200) String name,
                            String severity,
                            @Size(max = 200) String pattern,
                            @Size(max = 500) String description,
                            Boolean enabled) {
    }

    public record TestRequest(@NotBlank String patternType,
                              @Size(max = 200) String pattern,
                              @Size(max = 4000) String sampleText) {
    }

    @GetMapping
    public List<AlertRuleRow> list() {
        AdminGuard.requireStaff();
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRuleRow create(@Valid @RequestBody RuleRequest body,
                               HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        return service.create(body.name(), body.category(), body.severity(), body.patternType(),
                body.pattern(), body.locale(), body.description(),
                actor.userId(), AdminGuard.role(), clientIp(request));
    }

    @PatchMapping("/{ruleId}")
    public AlertRuleRow update(@PathVariable UUID ruleId,
                               @Valid @RequestBody RulePatch body,
                               HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        return service.update(ruleId, body.name(), body.severity(), body.pattern(),
                body.description(), body.enabled(),
                actor.userId(), AdminGuard.role(), clientIp(request));
    }

    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID ruleId, HttpServletRequest request) {
        JwtPrincipal actor = AdminGuard.requireStaff();
        service.delete(ruleId, actor.userId(), AdminGuard.role(), clientIp(request));
    }

    /** Live pattern preview for the rule editor; staff-scoped, no writes. */
    @PostMapping("/test")
    public Map<String, Object> test(@Valid @RequestBody TestRequest body) {
        AdminGuard.requireStaff();
        return service.test(body.patternType(), body.pattern(), body.sampleText());
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.rateLimitClientIp(request);
    }
}
