package com.nexachat.auth;

import com.nexachat.recovery.OtpPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.awaitility.Awaitility;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
@org.springframework.context.annotation.Import(AccountRecoveryFlowTest.MailTestConfiguration.class)
class AccountRecoverySecurityTest extends SecurityITBase {

    private static final String TEST_RESEND_KEY = "account-recovery-test-resend-key";

    @Autowired
    private AccountRecoveryFlowTest.CapturingMailSender mailSender;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void recoveryMailTestProperties(DynamicPropertyRegistry registry) {
        registry.add("nexachat.mail.resend.api-key", () -> TEST_RESEND_KEY);
    }

    @BeforeEach
    void clearCapturedMail() {
        mailSender.clear();
    }

    @Test
    void emailIsNormalizedOwnerOnlyAndRequiresVerification() throws Exception {
        Registered owner = register("+84905552001");
        Registered other = register("+84905552002");

        putEmail(owner, "  OWNER@Example.com ").andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp verification = mailSender.take();
        assertThat(verification).isNotNull();
        assertThat(verification.recipient()).isEqualTo("owner@example.com");
        assertThat(verification.purpose()).isEqualTo(OtpPurpose.VERIFY_EMAIL);
        assertThat(verification.code()).matches("\\d{6}");

        String storedHash = jdbcTemplate.queryForObject("SELECT code_hash FROM email_otps"
                        + " WHERE user_id = ? AND purpose = 'VERIFY_EMAIL'",
                String.class, owner.userId());
        assertThat(storedHash).hasSize(64).doesNotContain(verification.code());

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value((Object) null))
                .andExpect(jsonPath("$.emailVerified").value(false));

        String otherView = mockMvc.perform(get("/api/v1/users/search")
                        .param("phone", "+84905552001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(otherView).doesNotContain("owner@example.com").doesNotContain("emailVerified");

        mockMvc.perform(post("/api/v1/users/me/email/verify")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", verification.code()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("owner@example.com"))
                .andExpect(jsonPath("$.verified").value(true));

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true));

        mockMvc.perform(delete("/api/v1/users/me/email")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value((Object) null))
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void publicRequestsDoNotEnumerateAndLogsOmitOtpAndProviderKey(CapturedOutput output) throws Exception {
        register("+84905552003"); // Existing account without a verified email.
        Registered withEmail = registerWithVerifiedEmail("+84905552004", "safe@example.com");

        String unknown = requestCode("+84909992003");
        String noEmail = requestCode("+84905552003");
        String withEmailResponse = requestCode("+84905552004");
        assertThat(unknown).isEqualTo("{}");
        assertThat(noEmail).isEqualTo(unknown);
        assertThat(withEmailResponse).isEqualTo(unknown);

        AccountRecoveryFlowTest.SentOtp deletion = mailSender.take();
        assertThat(deletion).isNotNull();
        assertThat(deletion.recipient()).isEqualTo("safe@example.com");
        assertThat(deletion.purpose()).isEqualTo(OtpPurpose.DELETE_ACCOUNT);
        assertThat(withEmail.userId()).isNotNull();
        assertThat(output.getOut()).doesNotContain(deletion.code()).doesNotContain(TEST_RESEND_KEY);
    }

    @Test
    void recoveryEmailOwnershipDoesNotChangeStatusOrBody() throws Exception {
        registerWithVerifiedEmail("+84905552901", "claimed@example.com");
        Registered other = register("+84905552902");

        var first = putEmail(other, "claimed@example.com")
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(first.getResponse().getContentAsString()).isEmpty();

        for (int index = 3; index <= 6; index++) {
            Registered requester = register("+849055529%02d".formatted(index));
            var duplicate = putEmail(requester, "claimed@example.com")
                    .andExpect(status().isAccepted())
                    .andReturn();
            assertThat(duplicate.getResponse().getContentAsString()).isEmpty();
        }

        Registered rateLimited = register("+84905552907");
        var afterAddressLimit = putEmail(rateLimited, "claimed@example.com")
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(afterAddressLimit.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void emailOtpSendLimitAppliesAcrossAccountsForOneDestination() throws Exception {
        for (int i = 1; i <= 6; i++) {
            Registered user = register("+849055529%02d".formatted(i));
            var request = putEmail(user, "shared-destination@example.com");
            if (i <= 5) {
                request.andExpect(status().isAccepted());
                assertThat(mailSender.take()).isNotNull();
            } else {
                request.andExpect(status().isAccepted())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .content().string(""));
                assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM email_otps WHERE user_id = ? AND purpose = 'VERIFY_EMAIL'
                          AND consumed_at IS NULL
                        """, Integer.class, user.userId())).isZero();
            }
        }
    }

    @Test
    void emailOtpConfirmLimitAppliesAcrossCodesForOneAccount() throws Exception {
        Registered user = register("+84905552921");
        putEmail(user, "account-limited@example.com").andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp first = mailSender.take();
        String wrong = first.code().equals("000000") ? "000001" : "000000";

        for (int attempt = 0; attempt < 5; attempt++) {
            verifyEmail(user, wrong).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
        }

        ageLatestOtp(user.userId(), OtpPurpose.VERIFY_EMAIL);
        putEmail(user, "account-limited@example.com").andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp second = mailSender.take();
        for (int attempt = 0; attempt < 5; attempt++) {
            verifyEmail(user, wrong).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
        }

        verifyEmail(user, second.code()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void emailOtpConfirmLimitAppliesAcrossAccountsForOneDestination() throws Exception {
        java.util.List<Registered> users = new java.util.ArrayList<>();
        java.util.List<AccountRecoveryFlowTest.SentOtp> codes = new java.util.ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            Registered user = register("+849055528%02d".formatted(index));
            users.add(user);
            putEmail(user, "shared-confirm@example.com").andExpect(status().isAccepted());
            codes.add(mailSender.take());
        }

        for (int account = 0; account < 2; account++) {
            String wrong = codes.get(account).code().equals("000000") ? "000001" : "000000";
            for (int attempt = 0; attempt < 5; attempt++) {
                verifyEmail(users.get(account), wrong).andExpect(status().isBadRequest());
            }
        }

        verifyEmail(users.get(2), codes.get(2).code())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
    }

    @Test
    void failedEmailDeliveryKeepsPreviousVerifiedAddressAndDoesNotLogSecrets(CapturedOutput output)
            throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552922", "old-recovery@example.com");
        ageLatestOtp(user.userId(), OtpPurpose.VERIFY_EMAIL);
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        mailSender.failNextSend(sendEntered, releaseSend);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<org.springframework.test.web.servlet.MvcResult> request = executor.submit(
                    () -> putEmail(user, "new-recovery@example.com").andReturn());
            assertThat(sendEntered.await(10, TimeUnit.SECONDS)).isTrue();

            var accepted = request.get(5, TimeUnit.SECONDS);
            assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT email = 'old-recovery@example.com' AND email_verified_at IS NOT NULL"
                            + " FROM users WHERE id = ?", Boolean.class, user.userId())).isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM email_otps WHERE user_id = ? AND purpose = 'VERIFY_EMAIL'"
                            + " AND email = 'new-recovery@example.com' AND consumed_at IS NULL",
                    Integer.class, user.userId())).isEqualTo(1);
        } finally {
            releaseSend.countDown();
            executor.shutdownNow();
        }

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_otps WHERE user_id = ? AND purpose = 'VERIFY_EMAIL'"
                        + " AND email = 'new-recovery@example.com' AND consumed_at IS NULL",
                Integer.class, user.userId())).isZero());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT email = 'old-recovery@example.com' AND email_verified_at IS NOT NULL"
                        + " FROM users WHERE id = ?", Boolean.class, user.userId())).isTrue();
        assertThat(output.getOut()).doesNotContain("old-recovery@example.com")
                .doesNotContain("new-recovery@example.com")
                .doesNotContain("synthetic-provider-response-secret");
        AccountRecoveryFlowTest.SentOtp failedDelivery = mailSender.lastAttempted();
        assertThat(failedDelivery).isNotNull();
        assertThat(output.getOut()).doesNotContain(failedDelivery.code());
    }

    @Test
    void failedPublicOtpDeliveryConsumesTheIssuedCodeAndDoesNotLogSecrets(CapturedOutput output)
            throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552927", "failed-public-send@example.com");
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        mailSender.failNextSend(sendEntered, releaseSend);

        assertThat(requestCode("+84905552927")).isEqualTo("{}");
        assertThat(sendEntered.await(10, TimeUnit.SECONDS)).isTrue();
        AccountRecoveryFlowTest.SentOtp failedDelivery = mailSender.lastAttempted();
        assertThat(failedDelivery).isNotNull();
        assertThat(failedDelivery.purpose()).isEqualTo(OtpPurpose.DELETE_ACCOUNT);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM email_otps
                WHERE user_id = ? AND purpose = 'DELETE_ACCOUNT' AND consumed_at IS NULL
                """, Integer.class, user.userId())).isEqualTo(1);

        try {
            releaseSend.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                    jdbcTemplate.queryForObject("""
                            SELECT count(*) FROM email_otps
                            WHERE user_id = ? AND purpose = 'DELETE_ACCOUNT' AND consumed_at IS NULL
                            """, Integer.class, user.userId())).isZero());
        } finally {
            releaseSend.countDown();
        }

        assertThat(output.getOut()).doesNotContain("failed-public-send@example.com")
                .doesNotContain(failedDelivery.code())
                .doesNotContain("synthetic-provider-response-secret");
    }

    @Test
    void fiveWrongCodesKillTheOtpAndTheCorrectCodeStaysGeneric() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552005", "brute@example.com");
        requestCode("+84905552005");
        AccountRecoveryFlowTest.SentOtp issued = mailSender.take();
        String wrong = issued.code().equals("000000") ? "000001" : "000000";

        for (int attempt = 0; attempt < 5; attempt++) {
            confirmDeletion("+84905552005", wrong)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
        }
        Integer attempts = jdbcTemplate.queryForObject("SELECT attempts FROM email_otps WHERE user_id = ?"
                + " AND purpose = 'DELETE_ACCOUNT'", Integer.class, user.userId());
        assertThat(attempts).isEqualTo(5);

        confirmDeletion("+84905552005", issued.code())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
    }

    @Test
    void expiryReuseAndDeletedAccountRequestsRemainGeneric() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552006", "expiry@example.com");
        requestCode("+84905552006");
        AccountRecoveryFlowTest.SentOtp expired = mailSender.take();
        jdbcTemplate.update("UPDATE email_otps SET expires_at = ? WHERE user_id = ? AND purpose = 'DELETE_ACCOUNT'",
                Timestamp.from(Instant.now().minusSeconds(1)), user.userId());
        confirmDeletion("+84905552006", expired.code())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));

        ageLatestOtp(user.userId(), OtpPurpose.DELETE_ACCOUNT);
        requestCode("+84905552006");
        AccountRecoveryFlowTest.SentOtp current = mailSender.take();
        confirmDeletion("+84905552006", current.code()).andExpect(status().isNoContent());
        confirmDeletion("+84905552006", current.code())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
        assertThat(requestCode("+84905552006")).isEqualTo("{}");
        assertThat(mailSender.messages()).isEmpty();
    }

    @Test
    void otpDeletionUsesHardenedAccountDeletionAndClearsRecoveryData() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552012", "delete@example.com");
        requestCode("+84905552012");
        AccountRecoveryFlowTest.SentOtp deletion = mailSender.take();
        assertThat(deletion).isNotNull();

        Instant now = Instant.now();
        insertOtp(user.userId(), OtpPurpose.VERIFY_EMAIL, "pending@example.com", now);
        insertOtp(user.userId(), OtpPurpose.RESET_PASSWORD, "delete@example.com", now);

        jdbcTemplate.update("""
                INSERT INTO user_identities (id, user_id, provider, subject, email_at_link, linked_at)
                VALUES (?, ?, 'google', ?, 'linked@example.com', ?)
                """, java.util.UUID.randomUUID(), user.userId(), "deleted-subject-" + user.userId(),
                Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO google_signin_nonces
                    (id, nonce_hash, purpose, user_id, access_token_id_hash, created_at, expires_at)
                VALUES (?, ?, 'link', ?, ?, ?, ?)
                """, java.util.UUID.randomUUID(), hash64("a"), user.userId(), hash64("b"),
                Timestamp.from(now), Timestamp.from(now.plusSeconds(60)));

        confirmDeletion("+84905552012", deletion.code()).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, user.userId()))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT phone IS NULL AND email IS NULL AND email_verified_at IS NULL"
                        + " FROM users WHERE id = ?", Boolean.class, user.userId())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tokens_valid_after IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, user.userId())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_otps WHERE user_id = ?", Integer.class, user.userId()))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_identities WHERE user_id = ?", Integer.class, user.userId()))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM google_signin_nonces WHERE user_id = ?", Integer.class, user.userId()))
                .isZero();

        getWithAuth("/api/v1/users/me", user.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void newerOtpInvalidatesTheOldCodeAndVerifyCodeCannotDeleteAccount() throws Exception {
        Registered user = register("+84905552007");
        putEmail(user, "cross-purpose@example.com").andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp verifyCode = mailSender.take();

        confirmDeletion("+84905552007", verifyCode.code())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
        verifyEmail(user, verifyCode.code()).andExpect(status().isOk());

        requestCode("+84905552007");
        AccountRecoveryFlowTest.SentOtp old = mailSender.take();
        ageLatestOtp(user.userId(), OtpPurpose.DELETE_ACCOUNT);
        requestCode("+84905552007");
        AccountRecoveryFlowTest.SentOtp latest = mailSender.take();
        assertThat(latest.code()).isNotEqualTo(old.code());

        confirmDeletion("+84905552007", old.code())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"));
        confirmDeletion("+84905552007", latest.code()).andExpect(status().isNoContent());
    }

    @Test
    void cooldownAndPerPhoneRateLimitsAreEnforced() throws Exception {
        Registered user = register("+84905552008");
        putEmail(user, "cooldown@example.com").andExpect(status().isAccepted());
        mailSender.take();
        putEmail(user, "changed@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value((Object) null));

        Registered publicAccount = registerWithVerifiedEmail(
                "+84909992008", "delete-budget@example.com");
        for (int i = 0; i < 10; i++) {
            assertThat(requestCode("+84909992008")).isEqualTo("{}");
            assertThat(mailSender.take()).isNotNull();
            if (i < 9) {
                ageLatestOtp(publicAccount.userId(), OtpPurpose.DELETE_ACCOUNT);
            }
        }
        Integer otpRowsBefore = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM email_otps WHERE user_id = ? AND purpose = 'DELETE_ACCOUNT'
                """, Integer.class, publicAccount.userId());
        mockMvc.perform(post("/api/v1/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", "+84909992008"))))
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("{}", true));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM email_otps WHERE user_id = ? AND purpose = 'DELETE_ACCOUNT'
                """, Integer.class, publicAccount.userId())).isEqualTo(otpRowsBefore);
    }

    @Test
    void concurrentVerificationCannotClaimOneEmailForTwoUsers(CapturedOutput output) throws Exception {
        Registered first = register("+84905552010");
        Registered second = register("+84905552011");
        putEmail(first, "race@example.com").andExpect(status().isAccepted());
        putEmail(second, "race@example.com").andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp firstCode = mailSender.take();
        AccountRecoveryFlowTest.SentOtp secondCode = mailSender.take();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> firstResult = executor.submit(() -> verifyConcurrently(first, firstCode.code(), ready, start));
            Future<Integer> secondResult = executor.submit(() -> verifyConcurrently(second, secondCode.code(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int firstStatus = firstResult.get(10, TimeUnit.SECONDS);
            int secondStatus = secondResult.get(10, TimeUnit.SECONDS);

            assertThat(java.util.List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(200, 400);
            assertThat(output.getOut()).doesNotContain("race@example.com");
            Integer owners = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users"
                    + " WHERE email = 'race@example.com' AND email_verified_at IS NOT NULL AND deleted_at IS NULL",
                    Integer.class);
            assertThat(owners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void publicRequestIsLimitedPerIpAcrossPhoneValues() throws Exception {
        for (int i = 0; i < 30; i++) {
            requestCode("+849055531%03d".formatted(i));
        }
        mockMvc.perform(post("/api/v1/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", "+84905553999"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void publicOtpRequestsShareAnAccountBudgetAcrossPurposes() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552923", "shared-budget@example.com");
        for (int i = 0; i < 10; i++) {
            assertThat(requestCode("+84905552923")).isEqualTo("{}");
        }

        Integer before = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM email_otps
                WHERE user_id = ? AND purpose = 'RESET_PASSWORD' AND consumed_at IS NULL
                """, Integer.class, user.userId());
        var limitedAccount = postJson("/api/v1/auth/password-reset/request",
                        Map.of("phone", "+84905552923"), null,
                        request -> { request.setRemoteAddr("203.0.113.10"); return request; })
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("{}", true))
                .andReturn();
        var unknownAccount = postJson("/api/v1/auth/password-reset/request",
                        Map.of("phone", "+84905552926"), null,
                        request -> { request.setRemoteAddr("203.0.113.11"); return request; })
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("{}", true))
                .andReturn();
        assertThat(limitedAccount.getResponse().getContentAsString())
                .isEqualTo(unknownAccount.getResponse().getContentAsString());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM email_otps
                WHERE user_id = ? AND purpose = 'RESET_PASSWORD' AND consumed_at IS NULL
                """, Integer.class, user.userId())).isEqualTo(before);
    }

    @Test
    void publicOtpConfirmationsShareAnAccountBudgetAcrossPurposes() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552925", "confirm-budget@example.com");
        requestCode("+84905552925");
        mailSender.take();
        String wrong = "000000";
        for (int attempt = 0; attempt < 10; attempt++) {
            confirmDeletion("+84905552925", wrong).andExpect(status().isBadRequest());
        }

        requestPasswordReset("+84905552925");
        AccountRecoveryFlowTest.SentOtp reset = mailSender.take();
        var limitedAccount = confirmPasswordReset("+84905552925", reset.code(), "confirm-budget123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"))
                .andReturn();
        var unknownAccount = confirmPasswordReset("+84905552926", reset.code(), "confirm-budget123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OR_EXPIRED_CODE"))
                .andReturn();
        assertThat(limitedAccount.getResponse().getContentAsString())
                .isEqualTo(unknownAccount.getResponse().getContentAsString());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM email_otps
                WHERE user_id = ? AND purpose = 'RESET_PASSWORD' AND consumed_at IS NULL
                """, Integer.class, user.userId())).isEqualTo(1);
    }

    @Test
    void concurrentPasswordResetConfirmationConsumesTheCodeOnce() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552924", "single-use@example.com");
        requestPasswordReset("+84905552924");
        AccountRecoveryFlowTest.SentOtp reset = mailSender.take();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> confirmResetConcurrently(
                    "+84905552924", reset.code(), ready, start));
            Future<Integer> second = executor.submit(() -> confirmResetConcurrently(
                    "+84905552924", reset.code(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 400);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, user.userId())).isZero();
    }

    @Test
    void concurrentDeletionConfirmationConsumesTheCodeOnce() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552926", "single-use-delete@example.com");
        requestCode("+84905552926");
        AccountRecoveryFlowTest.SentOtp deletion = mailSender.take();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> confirmDeletionConcurrently(
                    "+84905552926", deletion.code(), ready, start));
            Future<Integer> second = executor.submit(() -> confirmDeletionConcurrently(
                    "+84905552926", deletion.code(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(java.util.List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 400);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, user.userId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL
                """, Integer.class, user.userId())).isZero();
    }

    @Test
    void passwordResetUsesVerifiedEmailAndRevokesAllSessions() throws Exception {
        Registered user = registerWithVerifiedEmail("+84905552009", "reset@example.com");
        Registered secondSession = login("+84905552009");
        String olderAccessToken = accessTokenIssuedBeforeRevocation(user.userId());

        requestPasswordReset("+84905552009");
        AccountRecoveryFlowTest.SentOtp reset = mailSender.take();
        assertThat(reset.purpose()).isEqualTo(OtpPurpose.RESET_PASSWORD);
        confirmPasswordReset("+84905552009", reset.code(), "new-secret123")
                .andExpect(status().isNoContent());

        postJson("/api/v1/auth/refresh", Map.of("refreshToken", user.refreshToken()))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", secondSession.refreshToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + olderAccessToken))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/login", Map.of("phone", "+84905552009", "password", "secret123"))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/login", Map.of("phone", "+84905552009", "password", "new-secret123"))
                .andExpect(status().isOk());
    }

    private Registered registerWithVerifiedEmail(String phone, String email)
            throws Exception {
        Registered user = register(phone);
        putEmail(user, email).andExpect(status().isAccepted());
        AccountRecoveryFlowTest.SentOtp code = mailSender.take();
        verifyEmail(user, code.code()).andExpect(status().isOk());
        return user;
    }

    private org.springframework.test.web.servlet.ResultActions putEmail(Registered user, String email)
            throws Exception {
        return mockMvc.perform(put("/api/v1/users/me/email")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email))));
    }

    private org.springframework.test.web.servlet.ResultActions verifyEmail(Registered user, String code)
            throws Exception {
        return mockMvc.perform(post("/api/v1/users/me/email/verify")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", code))));
    }

    private int verifyConcurrently(Registered user, String code, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("verification race was not released");
        }
        return verifyEmail(user, code).andReturn().getResponse().getStatus();
    }

    private int confirmResetConcurrently(String phone, String code,
                                         CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("password reset race was not released");
        }
        return confirmPasswordReset(phone, code, "concurrent-reset123")
                .andReturn().getResponse().getStatus();
    }

    private int confirmDeletionConcurrently(String phone, String code,
                                            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("account deletion race was not released");
        }
        return confirmDeletion(phone, code).andReturn().getResponse().getStatus();
    }

    private String requestCode(String phone) throws Exception {
        return mockMvc.perform(post("/api/v1/account-deletion/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone))))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private void requestPasswordReset(String phone) throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone))))
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("{}", true));
    }

    private org.springframework.test.web.servlet.ResultActions confirmDeletion(String phone, String code)
            throws Exception {
        return mockMvc.perform(post("/api/v1/account-deletion/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("phone", phone, "code", code))));
    }

    private org.springframework.test.web.servlet.ResultActions confirmPasswordReset(
            String phone, String code, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "phone", phone, "code", code, "newPassword", newPassword))));
    }

    private void ageLatestOtp(java.util.UUID userId, OtpPurpose purpose) {
        jdbcTemplate.update("UPDATE email_otps SET created_at = ?, expires_at = ?"
                        + " WHERE user_id = ? AND purpose = ?",
                Timestamp.from(Instant.now().minusSeconds(61)),
                Timestamp.from(Instant.now().plusSeconds(600)), userId, purpose.name());
    }

    private void insertOtp(java.util.UUID userId, OtpPurpose purpose, String email, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO email_otps
                    (id, user_id, purpose, email, code_hash, expires_at, attempts, consumed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, NULL, ?)
                """, java.util.UUID.randomUUID(), userId, purpose.name(), email, hash64("c"),
                Timestamp.from(now.plusSeconds(600)), Timestamp.from(now));
    }

    private static String hash64(String digit) {
        return digit.repeat(64);
    }
}
