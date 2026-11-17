package com.nexachat.auth;

import com.nexachat.recovery.OtpPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(AccountRecoveryFlowTest.MailTestConfiguration.class)
class EmailSignupFlowTest extends SecurityITBase {

    private static final String PASSWORD = "correct-horse-1";

    @Autowired
    private AccountRecoveryFlowTest.CapturingMailSender mailSender;

    @BeforeEach
    void clearCapturedMail() {
        mailSender.clear();
    }

    @Test
    void registersWithAMailedCodeAndSignsInWithTheEmail() throws Exception {
        String email = email();
        String code = requestCode(email);

        postJson("/api/v1/auth/register/email", registration(email, code, "Minh"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.displayName").value("Minh"));

        postJson("/api/v1/auth/login", Map.of("identifier", email, "password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void profileShowsAVerifiedEmailAndNoPhone() throws Exception {
        String email = email();
        String token = registerWithEmail(email);

        getWithAuth("/api/v1/users/me", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    void aWrongCodeIsRejectedAndTheRightOneStillWorks() throws Exception {
        String email = email();
        String code = requestCode(email);
        String wrong = "000000".equals(code) ? "111111" : "000000";

        postJson("/api/v1/auth/register/email", registration(email, wrong, "Minh"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
        postJson("/api/v1/auth/register/email", registration(email, code, "Minh"))
                .andExpect(status().isCreated());
    }

    @Test
    void aCodeWorksOnlyOnce() throws Exception {
        String email = email();
        String code = requestCode(email);
        postJson("/api/v1/auth/register/email", registration(email, code, "Minh"))
                .andExpect(status().isCreated());

        postJson("/api/v1/auth/register/email", registration(email, code, "Other"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
    }

    @Test
    void aCodeIsBoundToItsOwnAddress() throws Exception {
        String code = requestCode(email());

        postJson("/api/v1/auth/register/email", registration(email(), code, "Minh"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
    }

    @Test
    void anAlreadyRegisteredEmailGetsNoCode() throws Exception {
        String email = email();
        registerWithEmail(email);

        postJson("/api/v1/auth/register/email-code", Map.of("email", email))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_IN_USE"));
        assertThat(mailSender.messages()).isEmpty();
    }

    @Test
    void aSecondCodeWithinTheCooldownIsRefused() throws Exception {
        String email = email();
        requestCode(email);

        postJson("/api/v1/auth/register/email-code", Map.of("email", email))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void anUnavailableMailServiceIsReported() throws Exception {
        mailSender.setAvailable(false);

        postJson("/api/v1/auth/register/email-code", Map.of("email", email()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("MAIL_UNAVAILABLE"));
    }

    @Test
    void aMalformedEmailIsAValidationError() throws Exception {
        postJson("/api/v1/auth/register/email-code", Map.of("email", "not an email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void passwordCanBeResetWithTheEmailAlone() throws Exception {
        String email = email();
        registerWithEmail(email);
        mailSender.clear();

        postJson("/api/v1/auth/password-reset/request", Map.of("email", email))
                .andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp reset = mailSender.take();
        assertThat(reset).isNotNull();
        assertThat(reset.purpose()).isEqualTo(OtpPurpose.RESET_PASSWORD);
        assertThat(reset.recipient()).isEqualTo(email);

        postJson("/api/v1/auth/password-reset/confirm",
                Map.of("email", email, "code", reset.code(), "newPassword", "brand-new-pass-2"))
                .andExpect(status().isNoContent());

        postJson("/api/v1/auth/login", Map.of("identifier", email, "password", "brand-new-pass-2"))
                .andExpect(status().isOk());
        postJson("/api/v1/auth/login", Map.of("identifier", email, "password", PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anEmailAccountCanLinkAndUnlinkAPhone() throws Exception {
        String token = registerWithEmail(email());

        mockMvc.perform(put("/api/v1/users/me/phone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", "0905551771"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+84905551771"));

        mockMvc.perform(delete("/api/v1/users/me/phone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    void replacingALinkedPhoneNeedsTheCurrentPassword() throws Exception {
        String token = registerWithEmail(email());
        mockMvc.perform(put("/api/v1/users/me/phone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", "0905551781"))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/users/me/phone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", "0905551782"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("WRONG_PASSWORD"));

        mockMvc.perform(put("/api/v1/users/me/phone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", "0905551782", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+84905551782"));
    }

    @Test
    void theCodeMailFollowsTheLanguageOfTheRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register/email-code")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "vi-VN,vi;q=0.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email()))))
                .andExpect(status().isAccepted());
        assertThat(mailSender.take()).isNotNull();
        assertThat(mailSender.lastEmail().locale().getLanguage()).isEqualTo("vi");
        assertThat(mailSender.lastEmail().idempotencyKey()).startsWith("otp-");

        requestCode(email());
        assertThat(mailSender.lastEmail().locale().getLanguage()).isEqualTo("en");
    }

    @Test
    void aPhoneAlreadyInUseCannotBeLinked() throws Exception {
        register("+84905551772");
        String token = registerWithEmail(email());

        mockMvc.perform(put("/api/v1/users/me/phone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", "+84905551772"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PHONE_EXISTS"));
    }

    @Test
    void anOperatorPhoneCannotBeLinked() throws Exception {
        String token = registerWithEmail(email());

        mockMvc.perform(put("/api/v1/users/me/phone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", ADMIN_PHONE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PHONE_EXISTS"));
    }

    @Test
    void fiveWrongCodesKillTheCode() throws Exception {
        String email = email();
        String code = requestCode(email);
        String wrong = "000000".equals(code) ? "111111" : "000000";
        for (int attempt = 0; attempt < 5; attempt++) {
            postJson("/api/v1/auth/register/email", registration(email, wrong, "Minh"))
                    .andExpect(status().isBadRequest());
        }

        postJson("/api/v1/auth/register/email", registration(email, code, "Minh"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
    }

    @Test
    void aPhoneOnlyAccountCannotDropItsPhone() throws Exception {
        Registered user = register("+84905551773");

        mockMvc.perform(delete("/api/v1/users/me/phone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LAST_SIGN_IN_METHOD"));
    }

    private String requestCode(String email) throws Exception {
        postJson("/api/v1/auth/register/email-code", Map.of("email", email))
                .andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp sent = mailSender.take();
        assertThat(sent).isNotNull();
        assertThat(sent.purpose()).isEqualTo(OtpPurpose.REGISTER_EMAIL);
        assertThat(sent.recipient()).isEqualTo(email);
        return sent.code();
    }

    private String registerWithEmail(String email) throws Exception {
        String code = requestCode(email);
        String body = postJson("/api/v1/auth/register/email", registration(email, code, "Minh"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private static Map<String, String> registration(String email, String code, String name) {
        return Map.of("email", email, "code", code, "password", PASSWORD, "displayName", name);
    }

    private static String email() {
        return "u" + UUID.randomUUID().toString().replace("-", "") + "@example.test";
    }
}
