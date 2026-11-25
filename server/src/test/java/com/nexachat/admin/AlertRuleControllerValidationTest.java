package com.nexachat.admin;

import com.nexachat.common.error.GlobalExceptionHandler;
import com.nexachat.config.ClientIpResolver;
import com.nexachat.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer contract matrix for the alert-rule DELETE reason body (the
 * audit delete-reason fix): the body is optional, an absent or
 * null reason keeps the historical 204, a reason over 500 characters is a
 * 400 VALIDATION_FAILED, junk JSON is a 400, and a body shipped with a
 * non-JSON content type is rejected with 415 by the controller guard (the
 * shared advice maps the exception), instead of being silently dropped as a
 * null reason the way an unparseable optional body resolves by default.
 */
@ExtendWith(MockitoExtension.class)
class AlertRuleControllerValidationTest {

    private static final String RULE_PATH = "/api/v1/admin/rules/{ruleId}";

    @Mock
    private AlertRuleService service;
    @Mock
    private ClientIpResolver clientIpResolver;

    private MockMvc mockMvc;
    private UUID ruleId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AlertRuleController(service, clientIpResolver))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        ruleId = UUID.randomUUID();
        lenient().when(clientIpResolver.rateLimitClientIp(any())).thenReturn("127.0.0.1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPassesTheOptionalAuditReasonToTheService() throws Exception {
        signInAsAdmin();

        mockMvc.perform(post("/api/v1/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"name\":\"Payments keyword\","
                                + "\"category\":\"CUSTOM\","
                                + "\"severity\":\"MEDIUM\","
                                + "\"patternType\":\"KEYWORD\","
                                + "\"pattern\":\"invoice\","
                                + "\"reason\":\"new invoice scam wave\""
                                + "}"))
                .andExpect(status().isCreated());

        verify(service).create(eq("Payments keyword"), eq("CUSTOM"), eq("MEDIUM"), eq("KEYWORD"),
                eq("invoice"), isNull(), isNull(), eq("new invoice scam wave"), any(),
                eq(User.Role.ADMIN), any());
    }

    @Test
    void deleteWithoutBodyIsStill204WithNullReason() throws Exception {
        signInAsAdmin();

        mockMvc.perform(delete(RULE_PATH, ruleId))
                .andExpect(status().isNoContent());

        verify(service).delete(eq(ruleId), isNull(), any(), eq(User.Role.ADMIN), any());
    }

    @Test
    void deleteWithEmptyJsonObjectKeeps204AndNullReason() throws Exception {
        signInAsAdmin();

        mockMvc.perform(delete(RULE_PATH, ruleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent());

        verify(service).delete(eq(ruleId), isNull(), any(), eq(User.Role.ADMIN), any());
    }

    @Test
    void deleteWithExplicitNullReasonKeeps204() throws Exception {
        signInAsAdmin();

        mockMvc.perform(delete(RULE_PATH, ruleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": null}"))
                .andExpect(status().isNoContent());

        verify(service).delete(eq(ruleId), isNull(), any(), eq(User.Role.ADMIN), any());
    }

    @Test
    void deleteAcceptsAReasonOfExactly500Characters() throws Exception {
        signInAsAdmin();
        String boundary = "x".repeat(500);

        mockMvc.perform(delete(RULE_PATH, ruleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"" + boundary + "\"}"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(service).delete(eq(ruleId), reason.capture(), any(), eq(User.Role.ADMIN), any());
        assertThat(reason.getValue()).isEqualTo(boundary);
    }

    @Test
    void deleteRejectsAReasonOver500CharactersWith400() throws Exception {
        signInAsAdmin();

        mockMvc.perform(delete(RULE_PATH, ruleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void deleteRejectsJunkJsonWith400() throws Exception {
        signInAsAdmin();

        mockMvc.perform(delete(RULE_PATH, ruleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void deleteRejectsANonJsonContentTypeWith415() throws Exception {
        signInAsAdmin();

        mockMvc.perform(delete(RULE_PATH, ruleId)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("password123"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(service);
    }

    @Test
    void deleteRejectsAJsonScalarBodyWith400() throws Exception {
        signInAsAdmin();

        mockMvc.perform(delete(RULE_PATH, ruleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("2222"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    private void signInAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new com.nexachat.common.dto.JwtPrincipal(UUID.randomUUID()), null,
                List.of(() -> "ROLE_ADMIN")));
    }
}
