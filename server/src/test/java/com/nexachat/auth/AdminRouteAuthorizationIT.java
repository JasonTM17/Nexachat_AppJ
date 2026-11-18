package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.testutil.ItPhoneRanges;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actor-boundary coverage for the {@code /api/v1/admin/**} console surface:
 * the filter chain admits only ADMIN/MODERATOR roles, the finer splits inside
 * the services gate the destructive writes, and a suspension locks the
 * account out of every subsequent request — token included.
 *
 * <p>Referenced by the {@code admin_staff} role scenario in
 * {@code authz-route-matrix.tsv}; the method names are part of that contract.
 */
class AdminRouteAuthorizationIT extends SecurityITBase {

    private static final List<String> READ_ENDPOINTS = List.of(
            "/api/v1/admin/me",
            "/api/v1/admin/overview",
            "/api/v1/admin/system/health",
            "/api/v1/admin/audit",
            "/api/v1/admin/users",
            "/api/v1/admin/users/deleted",
            "/api/v1/admin/alerts",
            "/api/v1/admin/rules");

    @Test
    void adminReadEndpointsAllowStaffAndDenyUsers() throws Exception {
        Registered admin = register(ADMIN_PHONE);
        Registered user = register(ItPhoneRanges.phoneFor(AdminRouteAuthorizationIT.class, "+84907000101"));

        for (String path : READ_ENDPOINTS) {
            getWithAuth(path, admin.accessToken())
                    .andExpect(status().isOk());
            getWithAuth(path, user.accessToken())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
    }

    @Test
    void adminMutationEndpointsAllowAdminAndDenyModerator() throws Exception {
        Registered admin = register(ADMIN_PHONE);
        Registered moderator = register(ItPhoneRanges.phoneFor(AdminRouteAuthorizationIT.class, "+84907000102"));
        Registered target = register(ItPhoneRanges.phoneFor(AdminRouteAuthorizationIT.class, "+84907000103"));
        jdbcTemplate.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", moderator.userId());

        // Moderator reads pass the staff gate...
        getWithAuth("/api/v1/admin/users", moderator.accessToken()).andExpect(status().isOk());
        // ...and may suspend a plain user (an audited moderation action)...
        postJson("/api/v1/admin/users/" + target.userId() + "/suspend",
                Map.of("reason", "moderator probe"), moderator.accessToken())
                .andExpect(status().isNoContent());
        // ...but role changes and rule writes stay ADMIN-only.
        patchWithAuth("/api/v1/admin/users/" + target.userId() + "/role",
                Map.of("role", "MODERATOR", "reason", "probe"), moderator.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
        postJson("/api/v1/admin/rules",
                Map.of("name", "probe rule", "category", "CUSTOM", "severity", "LOW",
                        "patternType", "KEYWORD", "pattern", "probe"),
                moderator.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));

        // A plain user never reaches the service-level role split: the staff
        // filter rejects every admin mutation with the same FORBIDDEN the
        // reads return.
        Registered outsider = register(ItPhoneRanges.phoneFor(AdminRouteAuthorizationIT.class, "+84907000111"));
        patchWithAuth("/api/v1/admin/users/" + target.userId() + "/role",
                Map.of("role", "MODERATOR", "reason", "outsider probe"), outsider.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        postJson("/api/v1/admin/users/" + target.userId() + "/suspend",
                Map.of("reason", "outsider probe"), outsider.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        postJson("/api/v1/admin/users/" + target.userId() + "/actions",
                Map.of("reason", "outsider probe"), outsider.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        postJson("/api/v1/admin/rules",
                Map.of("name", "outsider rule", "category", "CUSTOM", "severity", "LOW",
                        "patternType", "KEYWORD", "pattern", "probe"),
                outsider.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // The configured admin performs both.
        patchWithAuth("/api/v1/admin/users/" + target.userId() + "/role",
                Map.of("role", "MODERATOR", "reason", "promote probe"), admin.accessToken())
                .andExpect(status().isOk());
        postJson("/api/v1/admin/rules",
                Map.of("name", "probe rule", "category", "CUSTOM", "severity", "LOW",
                        "patternType", "KEYWORD", "pattern", "probe"),
                admin.accessToken())
                .andExpect(status().isCreated());
    }

    @Test
    void anonymousAdminRequestsAreUnauthorized() throws Exception {
        for (String path : READ_ENDPOINTS) {
            mockMvc.perform(MockMvcRequestBuilders.get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
        // Mutations are bearer-gated at the same filter.
        String userId = UUID.randomUUID().toString();
        mockMvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/admin/users/" + userId + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MODERATOR\",\"reason\":\"anon probe\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/admin/users/" + userId + "/suspend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"anon probe\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"anon\",\"category\":\"CUSTOM\",\"severity\":\"LOW\","
                                + "\"patternType\":\"KEYWORD\",\"pattern\":\"probe\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void configuredAdminPhoneRegistersAsAdmin() throws Exception {
        Registered admin = register(ADMIN_PHONE);
        var result = getWithAuth("/api/v1/admin/me", admin.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("role").asText()).isEqualTo("ADMIN");
        assertThat(body.path("userId").asText()).isEqualTo(admin.userId().toString());
    }

    @Test
    void suspendedAccountIsLockedOutOfEveryAuthSurface() throws Exception {
        Registered admin = register(ADMIN_PHONE);
        Registered target = register(ItPhoneRanges.phoneFor(AdminRouteAuthorizationIT.class, "+84907000104"));

        postJson("/api/v1/admin/users/" + target.userId() + "/suspend",
                Map.of("reason", "lockout probe"), admin.accessToken())
                .andExpect(status().isNoContent());

        // Bearer access tokens die immediately — the revocation event evicts
        // the validity cache and the suspended flag rejects with 403.
        getWithAuth("/api/v1/users/me", target.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
        // Refresh rotation refuses too: suspension revoked the whole token
        // family, and presenting a revoked token is reported as reuse.
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", target.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_REUSED"));
        // And login refuses with the same honest code.
        postJson("/api/v1/auth/login", Map.of("phone", phoneOf(target.userId()), "password", "secret123"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    void unsuspendedAccountSignsInImmediately() throws Exception {
        Registered admin = register(ADMIN_PHONE);
        Registered target = register(ItPhoneRanges.phoneFor(AdminRouteAuthorizationIT.class, "+84907000110"));

        postJson("/api/v1/admin/users/" + target.userId() + "/suspend",
                Map.of("reason", "validity cache probe"), admin.accessToken())
                .andExpect(status().isNoContent());

        // A request while suspended fills the validity cache with the
        // suspended answer; unsuspend must not let it linger for the TTL.
        getWithAuth("/api/v1/users/me", target.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));

        postJson("/api/v1/admin/users/" + target.userId() + "/unsuspend",
                Map.of("reason", "validity cache probe"), admin.accessToken())
                .andExpect(status().isNoContent());

        // Suspend stamps the access-token cutoff at second resolution and a
        // token whose iat is not after it fails closed, so a login inside the
        // same wall-clock second mints an instantly-revoked token. Wait past
        // the second boundary before signing in.
        Thread.sleep(1100);

        var login = postJson("/api/v1/auth/login",
                Map.of("phone", phoneOf(target.userId()), "password", "secret123"))
                .andExpect(status().isOk())
                .andReturn();
        String freshToken = objectMapper
                .readTree(login.getResponse().getContentAsString())
                .path("accessToken").asText();

        getWithAuth("/api/v1/users/me", freshToken)
                .andExpect(status().isOk());
    }

    @Test
    void staffCannotActDestructivelyOnThemselves() throws Exception {
        Registered admin = register(ADMIN_PHONE);

        // Self-role-change and self-suspension are refused before the
        // last-admin guard: an operator can never lock themselves out.
        patchWithAuth("/api/v1/admin/users/" + admin.userId() + "/role",
                Map.of("role", "USER", "reason", "self demote probe"), admin.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        postJson("/api/v1/admin/users/" + admin.userId() + "/suspend",
                Map.of("reason", "self suspend probe"), admin.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void moderatorCannotActOnStaffAccounts() throws Exception {
        Registered admin = register(ADMIN_PHONE);
        Registered moderator = register(ItPhoneRanges.phoneFor(AdminRouteAuthorizationIT.class, "+84907000105"));
        Registered secondAdmin = register(ItPhoneRanges.phoneFor(AdminRouteAuthorizationIT.class, "+84907000109"));
        jdbcTemplate.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", moderator.userId());
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", secondAdmin.userId());

        // A moderator's reach stops at plain accounts: suspending a staff
        // account requires an ADMIN actor.
        postJson("/api/v1/admin/users/" + secondAdmin.userId() + "/suspend",
                Map.of("reason", "moderator overreach probe"), moderator.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
        postJson("/api/v1/admin/users/" + secondAdmin.userId() + "/suspend",
                Map.of("reason", "admin probe"), admin.accessToken())
                .andExpect(status().isNoContent());
    }

    @Test
    void warnUserCreatesANoticeTheTargetCanRead() throws Exception {
        Registered admin = register(ADMIN_PHONE);
        Registered target = register(ItPhoneRanges.phoneFor(AdminRouteAuthorizationIT.class, "+84907000106"));

        var action = postJson("/api/v1/admin/users/" + target.userId() + "/actions",
                Map.of("reason", "staff warning probe"), admin.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        UUID noticeId = UUID.fromString(objectMapper
                .readTree(action.getResponse().getContentAsString()).path("id").asText());

        var notices = getWithAuth("/api/v1/users/me/notices", target.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(notices.getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("id").asText()).isEqualTo(noticeId.toString());
        assertThat(list.get(0).path("kind").asText()).isEqualTo("WARNING");

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/me/notices/" + noticeId + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + target.accessToken()))
                .andExpect(status().isOk());
        // Another user cannot mark it read.
        Registered other = register(ItPhoneRanges.phoneFor(AdminRouteAuthorizationIT.class, "+84907000107"));
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/users/me/notices/" + noticeId + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void suspensionWritesAnAuditRow() throws Exception {
        Registered admin = register(ADMIN_PHONE);
        Registered target = register(ItPhoneRanges.phoneFor(AdminRouteAuthorizationIT.class, "+84907000108"));

        postJson("/api/v1/admin/users/" + target.userId() + "/suspend",
                Map.of("reason", "audit probe"), admin.accessToken())
                .andExpect(status().isNoContent());

        var audit = getWithAuth("/api/v1/admin/audit?action=USER_SUSPEND", admin.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(audit.getResponse().getContentAsString())
                .path("items");
        assertThat(items).hasSizeGreaterThanOrEqualTo(1);
        assertThat(items.get(0).path("actorId").asText()).isEqualTo(admin.userId().toString());
        assertThat(items.get(0).path("targetId").asText()).isEqualTo(target.userId().toString());
    }

    private String phoneOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT phone FROM users WHERE id = ?",
                String.class, userId);
    }
}
