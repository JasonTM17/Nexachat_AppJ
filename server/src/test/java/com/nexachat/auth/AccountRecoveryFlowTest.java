package com.nexachat.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.nexachat.mail.MailSender;
import com.nexachat.recovery.OtpPurpose;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(AccountRecoveryFlowTest.MailTestConfiguration.class)
class AccountRecoveryFlowTest extends SecurityITBase {

    @Autowired
    private CapturingMailSender mailSender;

    @BeforeEach
    void clearCapturedMail() {
        mailSender.clear();
    }

    @Test
    void publicDeletionRequestAlwaysReturnsAnEmptyAcceptedResponse() throws Exception {
        postJson("/api/v1/account-deletion/request", Map.of("phone", "+84909990001"))
                .andExpect(status().isAccepted())
                .andExpect(content().json("{}", true));
    }

    @Test
    void unknownDeletionConfirmationHasOneGenericCodeError() throws Exception {
        postJson("/api/v1/account-deletion/confirm", Map.of(
                "phone", "+84909990002", "code", "123456"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
    }

    @Test
    void publicPasswordResetRequestAlwaysReturnsAnEmptyAcceptedResponse() throws Exception {
        postJson("/api/v1/auth/password-reset/request", Map.of("phone", "+84909990003"))
                .andExpect(status().isAccepted())
                .andExpect(content().json("{}", true));
    }

    @Test
    void unknownPasswordResetConfirmationHasOneGenericCodeError() throws Exception {
        postJson("/api/v1/auth/password-reset/confirm", Map.of(
                "phone", "+84909990004", "code", "123456", "newPassword", "newpass123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
    }

    @Test
    void authenticatedRecoveryEmailCanBeRequested() throws Exception {
        Registered user = register("+84905551991");

        mockMvc.perform(put("/api/v1/users/me/email")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "owner@example.com"))))
                .andExpect(status().isAccepted());
    }

    @Test
    void disabledMailKeepsPublicRequestNeutralAndAuthenticatedEmailReturnsUnavailable() throws Exception {
        Registered user = register("+84905551993");
        mockMvc.perform(put("/api/v1/users/me/email")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "owner@example.com"))))
                .andExpect(status().isAccepted());
        SentOtp verification = mailSender.take();
        mockMvc.perform(post("/api/v1/users/me/email/verify")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", verification.code()))))
                .andExpect(status().isOk());

        mailSender.setAvailable(false);
        postJson("/api/v1/account-deletion/request", Map.of("phone", "+84905551993"))
                .andExpect(status().isAccepted())
                .andExpect(content().json("{}", true));
        mockMvc.perform(put("/api/v1/users/me/email")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "new@example.com"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("MAIL_UNAVAILABLE"));
        org.assertj.core.api.Assertions.assertThat(mailSender.messages()).isEmpty();
    }

    @Test
    void browserCorsIsLimitedToTheConfiguredDeletionRoutes() throws Exception {
        mockMvc.perform(options("/api/v1/account-deletion/request")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));

        mockMvc.perform(options("/api/v1/auth/password-reset/request")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        Registered user = register("+84905551992");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/users/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailTestConfiguration {
        @Bean
        @Primary
        CapturingMailSender capturingMailSender() {
            return new CapturingMailSender();
        }
    }

    static final class CapturingMailSender implements MailSender {
        private final LinkedBlockingQueue<SentOtp> sent = new LinkedBlockingQueue<>();
        private volatile boolean available = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public void sendOtp(String recipient, OtpPurpose purpose, String code) {
            sent.add(new SentOtp(recipient, purpose, code));
        }

        List<SentOtp> messages() {
            return List.copyOf(sent);
        }

        void clear() {
            sent.clear();
            available = true;
        }

        void setAvailable(boolean available) {
            this.available = available;
        }

        SentOtp take() throws InterruptedException {
            return sent.poll(5, TimeUnit.SECONDS);
        }
    }

    record SentOtp(String recipient, OtpPurpose purpose, String code) {
    }
}
