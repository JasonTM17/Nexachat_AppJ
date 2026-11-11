package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.attachment.AttachmentService;
import com.nexachat.attachment.AvatarController;
import com.nexachat.attachment.StorageService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.testutil.TestWaits;
import com.nexachat.recovery.EmailOtpService;
import com.nexachat.recovery.OtpMail;
import com.nexachat.recovery.OtpPurpose;
import com.nexachat.user.AccountDeletionService;
import com.nexachat.user.AccountDeletionCoordination;
import com.nexachat.user.DeletedAccountPolicy;
import com.nexachat.user.UserRepository;
import com.nexachat.user.dto.UserResponse;
import com.nexachat.ws.StompAuthChannelInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.awaitility.core.ConditionTimeoutException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(AccountDeletionD4RegressionIT.PauseConfig.class)
class AccountDeletionD4RegressionIT extends SecurityITBase {

    private static final byte[] PNG_BYTES = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    };

    @Autowired
    private TestAccountDeletionPause pause;

    @Autowired
    private StorageService storageService;

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private AvatarController avatarController;

    @Autowired
    private AccountDeletionService accountDeletionService;

    @Autowired
    private EmailOtpService emailOtpService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Autowired
    private TestRefreshBoundaryProbe refreshBoundaryProbe;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${nexachat.storage.root}")
    private String storageRoot;

    @BeforeEach
    void resetPause() {
        pause.reset();
        refreshBoundaryProbe.reset();
    }

    @Test
    void concurrentProfilePatchCannotReviveTombstone() throws Exception {
        Registered user = register("+84905553101", "secret123");
        CountDownLatch patchMayProceed = new CountDownLatch(1);
        pause.whenBeforeTombstone = () -> {
            patchMayProceed.countDown();
            try {
                pause.awaitResume();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> deleteStatus = executor.submit(() -> deleteMe(user.accessToken(), "secret123")
                    .andReturn().getResponse().getStatus());
            assertThat(patchMayProceed.await(30, TimeUnit.SECONDS)).isTrue();

            Future<org.springframework.test.web.servlet.MvcResult> patchResult = executor.submit(() -> {
                try {
                    return patchJson("/api/v1/users/me", Map.of("displayName", "Revived name"),
                            user.accessToken()).andReturn();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            pause.resume();
            assertThat(deleteStatus.get(60, TimeUnit.SECONDS)).isEqualTo(204);
            var patchMvc = patchResult.get(60, TimeUnit.SECONDS);
            var patchResponse = patchMvc.getResponse();
            assertThat(patchResponse.getStatus()).isIn(401, 404);
            if (patchResponse.getStatus() == 401) {
                assertThat(objectMapper.readTree(patchResponse.getContentAsString())
                        .get("error").get("code").asText()).isEqualTo("TOKEN_INVALID");
            }

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, user.userId()))
                    .isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT phone IS NULL FROM users WHERE id = ?", Boolean.class, user.userId())).isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT tokens_valid_after IS NOT NULL FROM users WHERE id = ?",
                    Boolean.class, user.userId())).isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT display_name FROM users WHERE id = ?", String.class, user.userId()))
                    .isEqualTo(DeletedAccountPolicy.DISPLAY_NAME);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deletionSetsAccessTokenCutoffAndRevokesRefreshTokens() throws Exception {
        Registered user = register("+84905553102", "secret123");
        deleteMe(user.accessToken(), "secret123").andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT tokens_valid_after IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, user.userId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) = 0 FROM refresh_tokens
                WHERE user_id = ? AND revoked_at IS NULL
                """, Boolean.class, user.userId())).isTrue();
    }

    @Test
    void refreshWaitingBehindDeletionFailsAsInvalidWithoutCreatingASession() throws Exception {
        Registered user = register("+84905553119", "secret123");
        CountDownLatch deletionAtTombstone = new CountDownLatch(1);
        CountDownLatch refreshAtOwnerLock = new CountDownLatch(1);
        CountDownLatch allowRefreshToLockOwner = new CountDownLatch(1);
        pause.whenBeforeTombstone = () -> {
            deletionAtTombstone.countDown();
            try {
                pause.awaitResume();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        };
        refreshBoundaryProbe.beforeRotateUserLock = () -> {
            refreshAtOwnerLock.countDown();
            try {
                if (!allowRefreshToLockOwner.await(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out before refresh could lock its owner");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> deletion = executor.submit(() -> deleteMe(user.accessToken(), "secret123")
                    .andReturn().getResponse().getStatus());
            assertThat(deletionAtTombstone.await(30, TimeUnit.SECONDS)).isTrue();

            Future<org.springframework.test.web.servlet.MvcResult> refresh = executor.submit(() -> {
                try {
                    return postJson("/api/v1/auth/refresh", Map.of(
                            "refreshToken", user.refreshToken())).andReturn();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertThat(refreshAtOwnerLock.await(30, TimeUnit.SECONDS)).isTrue();

            pause.resume();
            assertThat(deletion.get(60, TimeUnit.SECONDS)).isEqualTo(204);
            allowRefreshToLockOwner.countDown();

            var refreshResult = refresh.get(60, TimeUnit.SECONDS);
            assertThat(refreshResult.getResponse().getStatus()).isEqualTo(401);
            assertThat(objectMapper.readTree(refreshResult.getResponse().getContentAsString())
                    .path("error").path("code").asText()).isEqualTo("TOKEN_INVALID");
        } finally {
            pause.resume();
            allowRefreshToLockOwner.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL
                """, Integer.class, user.userId())).isZero();
    }

    @Test
    void recoveryOtpDeletionWaitingLoginCannotIssueSession() throws Exception {
        Registered user = register("+84905553120", "secret123");
        jdbcTemplate.update("UPDATE users SET email = ?, email_verified_at = ? WHERE id = ?",
                "delete-login-race@example.com", Timestamp.from(Instant.now()), user.userId());
        OtpMail deletionCode = emailOtpService.issueForPublicRequest(user.userId(), OtpPurpose.DELETE_ACCOUNT)
                .orElseThrow();

        CountDownLatch deletionAtTombstone = new CountDownLatch(1);
        pause.whenBeforeTombstone = () -> {
            deletionAtTombstone.countDown();
            try {
                pause.awaitResume();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> deletion = executor.submit(() -> postJson(
                    "/api/v1/account-deletion/confirm",
                    Map.of("phone", "+84905553120", "code", deletionCode.code()))
                    .andReturn().getResponse().getStatus());
            assertThat(deletionAtTombstone.await(30, TimeUnit.SECONDS)).isTrue();

            Future<org.springframework.test.web.servlet.MvcResult> login = executor.submit(() -> postJson(
                    "/api/v1/auth/login", Map.of("phone", "+84905553120", "password", "secret123"))
                    .andReturn());
            TestWaits.until("login to wait on the account row held by recovery deletion",
                    () -> userLockWaiterCount() > 0);

            pause.resume();
            assertThat(deletion.get(60, TimeUnit.SECONDS)).isEqualTo(204);
            var loginResult = login.get(60, TimeUnit.SECONDS);
            assertThat(loginResult.getResponse().getStatus()).isEqualTo(401);
            assertThat(objectMapper.readTree(loginResult.getResponse().getContentAsString())
                    .path("error").path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        } finally {
            pause.resume();
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL AND phone IS NULL FROM users WHERE id = ?",
                Boolean.class, user.userId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL
                """, Integer.class, user.userId())).isZero();
    }

    @Test
    void deletionRejectsPreviouslyIssuedAccessTokenOnStompConnect() throws Exception {
        Registered user = register("+84905553117", "secret123");
        deleteMe(user.accessToken(), "secret123").andExpect(status().isNoContent());

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + user.accessToken());
        accessor.setLeaveMutable(true);
        Message<?> connect = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        MessageChannel channel = org.mockito.Mockito.mock(MessageChannel.class);

        assertThat(stompAuthChannelInterceptor.preSend(connect, channel)).isNull();
    }

    @Test
    void deletionScrubsAiTablesAndCaches() throws Exception {
        Registered user = register("+84905553103", "secret123");
        Registered peer = register("+84905553104", "secret123");
        UUID conversationId = openDirect(user, peer);
        UUID groupId = createGroup(user, List.of(peer.userId()));

        jdbcTemplate.update(
                "INSERT INTO conversation_ai_opt_outs (conversation_id, user_id) VALUES (?, ?)",
                conversationId, user.userId());
        jdbcTemplate.update("""
                INSERT INTO summary_request_limits (user_id, window_type, window_start, request_count)
                VALUES (?, 'DAY', ?, 1)
                """, user.userId(), Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
        jdbcTemplate.update("""
                INSERT INTO rag_ask_request_limits (user_id, window_type, window_start, request_count)
                VALUES (?, 'DAY', ?, 1)
                """, user.userId(), Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
        UUID summaryId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO message_summaries
                (id, conversation_id, from_seq, to_seq, content_hash, provider, language, payload_json, expires_at)
                VALUES (?, ?, 1, 1, ?, 'LOCAL', 'EN', '{}'::jsonb, ?)
                """, summaryId, conversationId, "a".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        UUID cacheId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO rag_ask_cache
                (id, conversation_id, model, question_hash, question_vector, language, payload_json,
                 cited_message_ids, cited_content_hash, expires_at)
                VALUES (?, ?, 'test', ?, '\\x00', 'EN', '{}'::jsonb, '{}', ?, ?)
                """, cacheId, conversationId, "b".repeat(64), "c".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbcTemplate.update("""
                INSERT INTO message_summaries
                (id, conversation_id, from_seq, to_seq, content_hash, provider, language, payload_json, expires_at)
                VALUES (?, ?, 1, 1, ?, 'LOCAL', 'EN', '{}'::jsonb, ?)
                """, UUID.randomUUID(), groupId, "d".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbcTemplate.update("""
                INSERT INTO rag_ask_cache
                (id, conversation_id, model, question_hash, question_vector, language, payload_json,
                 cited_message_ids, cited_content_hash, expires_at)
                VALUES (?, ?, 'test', ?, '\\x00', 'EN', '{}', '{}', ?, ?)
                """, UUID.randomUUID(), groupId, "e".repeat(64), "f".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600)));

        deleteMe(user.accessToken(), "secret123").andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_ai_opt_outs WHERE user_id = ?",
                Integer.class, user.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM summary_request_limits WHERE user_id = ?",
                Integer.class, user.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_ask_request_limits WHERE user_id = ?",
                Integer.class, user.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message_summaries WHERE conversation_id = ?",
                Integer.class, conversationId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_ask_cache WHERE conversation_id = ?",
                Integer.class, conversationId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message_summaries WHERE conversation_id = ?",
                Integer.class, groupId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_ask_cache WHERE conversation_id = ?",
                Integer.class, groupId)).isZero();
    }

    @Test
    void deletionAdvancesAiConsentEpochWhenConsentWasAlreadyFalse() throws Exception {
        Registered user = register("+84905553105", "secret123");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ai_summary_consent FROM users WHERE id = ?", Boolean.class, user.userId())).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ai_summary_consent_epoch FROM users WHERE id = ?", Long.class, user.userId()))
                .isZero();

        deleteMe(user.accessToken(), "secret123").andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT ai_summary_consent_epoch FROM users WHERE id = ?", Long.class, user.userId()))
                .isEqualTo(1);
    }

    @Test
    void concurrentGroupAddCannotOutliveAccountDeletion() throws Exception {
        Registered victim = register("+84905553201", "secret123");
        Registered admin = register("+84905553202", "secret123");
        Registered existing = register("+84905553203", "secret123");
        UUID groupId = createGroup(admin, List.of(existing.userId()));

        assertAccountDeletionWinsRace(victim, () -> postJson(
                "/api/v1/conversations/" + groupId + "/members",
                Map.of("userIds", List.of(victim.userId().toString())), admin.accessToken())
                .andReturn().getResponse().getStatus());

        assertThat(memberCount(groupId, victim.userId())).isZero();
    }

    @Test
    void concurrentGroupCreationCannotIncludeDeletingUser() throws Exception {
        Registered victim = register("+84905553204", "secret123");
        Registered actor = register("+84905553205", "secret123");

        assertAccountDeletionWinsRace(victim, () -> postJson(
                "/api/v1/conversations/group",
                Map.of("title", "Deletion race", "memberIds", List.of(victim.userId().toString())),
                actor.accessToken()).andReturn().getResponse().getStatus());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_members WHERE user_id = ?",
                Integer.class, victim.userId())).isZero();
    }

    @Test
    void concurrentDirectCreationCannotIncludeDeletingUser() throws Exception {
        Registered victim = register("+84905553206", "secret123");
        Registered actor = register("+84905553207", "secret123");

        assertAccountDeletionWinsRace(victim, () -> postJson(
                "/api/v1/conversations/direct",
                Map.of("userId", victim.userId().toString()), actor.accessToken())
                .andReturn().getResponse().getStatus());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_members WHERE user_id = ?",
                Integer.class, victim.userId())).isZero();
    }

    @Test
    void concurrentDirectInviteJoinCannotOutliveAccountDeletion() throws Exception {
        Registered victim = register("+84905553208", "secret123");
        Registered owner = register("+84905553209", "secret123");
        Registered existing = register("+84905553210", "secret123");
        UUID groupId = createGroup(owner, List.of(existing.userId()));
        String inviteToken = createInviteLink(owner, groupId, false);

        assertAccountDeletionWinsRace(victim, () -> postJson(
                "/api/v1/invite-links/" + inviteToken + "/join", Map.of(), victim.accessToken())
                .andReturn().getResponse().getStatus());

        assertThat(memberCount(groupId, victim.userId())).isZero();
    }

    @Test
    void concurrentApprovalInviteJoinCannotPersistRequestAfterDeletionCleanup() throws Exception {
        Registered victim = register("+84905553211", "secret123");
        Registered owner = register("+84905553212", "secret123");
        Registered existing = register("+84905553213", "secret123");
        UUID groupId = createGroup(owner, List.of(existing.userId()));
        String inviteToken = createInviteLink(owner, groupId, true);

        assertAccountDeletionWinsRace(victim, () -> postJson(
                "/api/v1/invite-links/" + inviteToken + "/join", Map.of(), victim.accessToken())
                .andReturn().getResponse().getStatus());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM group_join_requests WHERE user_id = ?",
                Integer.class, victim.userId())).isZero();
    }

    @Test
    void concurrentApprovalCannotAddPendingUserAfterDeletionCleanup() throws Exception {
        Registered victim = register("+84905553214", "secret123");
        Registered owner = register("+84905553215", "secret123");
        Registered existing = register("+84905553216", "secret123");
        UUID groupId = createGroup(owner, List.of(existing.userId()));
        String inviteToken = createInviteLink(owner, groupId, true);
        var join = postJson("/api/v1/invite-links/" + inviteToken + "/join",
                        Map.of(), victim.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        UUID requestId = UUID.fromString(objectMapper.readTree(
                join.getResponse().getContentAsString()).get("requestId").asText());

        assertAccountDeletionWinsRace(victim, () -> postJson(
                "/api/v1/conversations/" + groupId + "/join-requests/" + requestId + "/approve",
                Map.of(), owner.accessToken()).andReturn().getResponse().getStatus());

        assertThat(memberCount(groupId, victim.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM group_join_requests WHERE user_id = ?",
                Integer.class, victim.userId())).isZero();
    }

    @Test
    void deletedUserCannotBeAddedToGroupOrDirect() throws Exception {
        Registered victim = register("+84905553105", "secret123");
        Registered actor = register("+84905553106", "secret123");
        deleteMe(victim.accessToken(), "secret123").andExpect(status().isNoContent());

        postJson("/api/v1/conversations/direct", Map.of("userId", victim.userId().toString()),
                actor.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

        postJson("/api/v1/conversations/group",
                Map.of("title", "No deleted", "memberIds", List.of(victim.userId().toString())),
                actor.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    void deletionRemovesUnboundUploadsLabelsAndPasswordHash() throws Exception {
        Registered user = register("+84905553107", "secret123");
        UUID attachmentId = UUID.randomUUID();
        StorageService.StoredFile stored = storageService.store(
                "attachments", "secret.png", "image/png", PNG_BYTES);
        jdbcTemplate.update("""
                INSERT INTO message_attachments
                (id, owner_id, storage_key, original_name, mime_type, size_bytes, created_at)
                VALUES (?, ?, ?, 'secret.png', 'image/png', ?, now())
                """, attachmentId, user.userId(), stored.storageKey(), stored.sizeBytes());
        UUID labelId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO conversation_labels (id, user_id, name, color_id) VALUES (?, ?, 'Private', 'INDIGO')",
                labelId, user.userId());
        Registered peer = register("+84905553118", "secret123");
        UUID conversationId = openDirect(user, peer);
        jdbcTemplate.update("""
                INSERT INTO conversation_label_assignments (conversation_id, user_id, label_id)
                VALUES (?, ?, ?)
                """, conversationId, user.userId(), labelId);
        assertThat(storageService.exists(stored.storageKey())).isTrue();

        deleteMe(user.accessToken(), "secret123").andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message_attachments WHERE owner_id = ?",
                Integer.class, user.userId())).isZero();
        assertThat(storageService.exists(stored.storageKey())).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_labels WHERE user_id = ?",
                Integer.class, user.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_label_assignments WHERE user_id = ?",
                Integer.class, user.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, user.userId()))
                .isEqualTo(DeletedAccountPolicy.TOMBSTONE_PASSWORD_HASH);
    }

    @Test
    void concurrentAttachmentUploadCannotOutliveDeletionCleanup() throws Exception {
        Registered user = register("+84905553217", "secret123");
        Set<String> attachmentsBefore = storedKeys("attachments");

        assertAccountDeletionWinsRace(user, () -> uploadAttachment(user));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message_attachments WHERE owner_id = ?",
                Integer.class, user.userId())).isZero();
        assertThat(storedKeys("attachments")).isEqualTo(attachmentsBefore);
    }

    @Test
    void attachmentUploadDeletesStoredFileWhenDatabaseTransactionRollsBack() throws Exception {
        Registered user = register("+84905553218", "secret123");
        java.util.concurrent.atomic.AtomicReference<String> uploadedUrl =
                new java.util.concurrent.atomic.AtomicReference<>();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            uploadedUrl.set(attachmentService.upload(user.userId(),
                    new MockMultipartFile("file", "rollback.png", "image/png", PNG_BYTES),
                    null, null, null, null).url());
            throw new IllegalStateException("planned attachment rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("planned attachment rollback");

        String storageKey = storageKeyFromUrl(uploadedUrl.get());
        assertThat(storageService.exists(storageKey)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message_attachments WHERE owner_id = ?",
                Integer.class, user.userId())).isZero();
    }

    @Test
    void avatarReplacementDeletesOldFileAfterCommit() throws Exception {
        Registered user = register("+84905553219", "secret123");
        String firstUrl = uploadAvatar(user).getResponse().getContentAsString();
        String firstAvatarUrl = objectMapper.readTree(firstUrl).get("avatarUrl").asText();
        String firstKey = storageKeyFromUrl(firstAvatarUrl);
        assertThat(storageService.exists(firstKey)).isTrue();

        String secondBody = uploadAvatar(user).getResponse().getContentAsString();
        String secondAvatarUrl = objectMapper.readTree(secondBody).get("avatarUrl").asText();
        String secondKey = storageKeyFromUrl(secondAvatarUrl);

        assertThat(secondKey).isNotEqualTo(firstKey);
        assertThat(storageService.exists(firstKey)).isFalse();
        assertThat(storageService.exists(secondKey)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avatar_url FROM users WHERE id = ?", String.class, user.userId()))
                .isEqualTo(secondAvatarUrl);
    }

    @Test
    void avatarReplacementRollbackKeepsOldFileAndDeletesNewFile() throws Exception {
        Registered user = register("+84905553220", "secret123");
        String firstBody = uploadAvatar(user).getResponse().getContentAsString();
        String firstUrl = objectMapper.readTree(firstBody).get("avatarUrl").asText();
        String firstKey = storageKeyFromUrl(firstUrl);
        java.util.concurrent.atomic.AtomicReference<String> attemptedUrl =
                new java.util.concurrent.atomic.AtomicReference<>();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            try {
                attemptedUrl.set(avatarController.uploadAvatar(new JwtPrincipal(user.userId()),
                        List.<org.springframework.web.multipart.MultipartFile>of(
                                new MockMultipartFile("file", "rollback.png", "image/png", PNG_BYTES)))
                        .avatarUrl());
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            throw new IllegalStateException("planned avatar rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("planned avatar rollback");

        String attemptedKey = storageKeyFromUrl(attemptedUrl.get());
        assertThat(storageService.exists(firstKey)).isTrue();
        assertThat(storageService.exists(attemptedKey)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avatar_url FROM users WHERE id = ?", String.class, user.userId()))
                .isEqualTo(firstUrl);
    }

    @Test
    void concurrentAvatarUploadRejectedByDeletionLeavesNoStoredFile() throws Exception {
        Registered user = register("+84905553221", "secret123");
        Set<String> avatarsBefore = storedKeys("avatars");

        assertAccountDeletionWinsRace(user, () -> uploadAvatarStatus(user));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT avatar_url FROM users WHERE id = ?", String.class, user.userId())).isNull();
        assertThat(storedKeys("avatars")).isEqualTo(avatarsBefore);
    }

    @Test
    void avatarFileIsDeletedOnlyAfterAccountDeletionTransactionCommits() throws Exception {
        Registered user = register("+84905553119", "secret123");
        StorageService.StoredFile avatar = storageService.store(
                "avatars", "avatar.png", "image/png", PNG_BYTES);
        jdbcTemplate.update("UPDATE users SET avatar_url = ? WHERE id = ?",
                storageService.resolveUrl(avatar.storageKey()), user.userId());
        assertThat(storageService.exists(avatar.storageKey())).isTrue();

        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
                accountDeletionService.deleteAccount(user.userId(), "secret123");
                throw new IllegalStateException("rollback account deletion after scheduling file cleanup");
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("rollback account deletion after scheduling file cleanup");

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT deleted_at IS NULL FROM users WHERE id = ?", Boolean.class, user.userId()))
                    .isTrue();
            assertThat(storageService.exists(avatar.storageKey())).isTrue();
        } finally {
            storageService.deleteIfExists(avatar.storageKey());
        }
    }

    @Test
    void accountDeletionKeepsAvatarFileStillReferencedByAGroup() throws Exception {
        Registered user = register("+84905553222", "secret123");
        Registered peer = register("+84905553223", "secret123");
        UUID groupId = createGroup(user, List.of(peer.userId()));
        String avatarBody = uploadAvatar(user).getResponse().getContentAsString();
        String avatarUrl = objectMapper.readTree(avatarBody).path("avatarUrl").asText();
        String avatarKey = storageKeyFromUrl(avatarUrl);

        patchJson("/api/v1/conversations/" + groupId, Map.of("avatarUrl", avatarUrl), user.accessToken())
                .andExpect(status().isOk());
        assertThat(storageService.exists(avatarKey)).isTrue();

        try {
            deleteMe(user.accessToken(), "secret123").andExpect(status().isNoContent());

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT avatar_url FROM conversations WHERE id = ?", String.class, groupId))
                    .isEqualTo(avatarUrl);
            assertThat(storageService.exists(avatarKey)).isTrue();
        } finally {
            storageService.deleteIfExists(avatarKey);
        }
    }

    @Test
    void avatarReplacementKeepsOldFileStillReferencedByAGroup() throws Exception {
        Registered user = register("+84905553224", "secret123");
        Registered peer = register("+84905553225", "secret123");
        UUID groupId = createGroup(user, List.of(peer.userId()));
        String firstBody = uploadAvatar(user).getResponse().getContentAsString();
        String firstUrl = objectMapper.readTree(firstBody).path("avatarUrl").asText();
        String firstKey = storageKeyFromUrl(firstUrl);
        patchJson("/api/v1/conversations/" + groupId, Map.of("avatarUrl", firstUrl), user.accessToken())
                .andExpect(status().isOk());

        try {
            String secondBody = uploadAvatar(user).getResponse().getContentAsString();
            String secondUrl = objectMapper.readTree(secondBody).path("avatarUrl").asText();

            assertThat(secondUrl).isNotEqualTo(firstUrl);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT avatar_url FROM conversations WHERE id = ?", String.class, groupId))
                    .isEqualTo(firstUrl);
            assertThat(storageService.exists(firstKey)).isTrue();
        } finally {
            storageService.deleteIfExists(firstKey);
        }
    }

    @Test
    void groupLeaveOnDeletionUsesDeletedAccountSentinel() throws Exception {
        Registered admin = register("+84905553108", "secret123");
        Registered member = register("+84905553109", "secret123");
        setDisplayName(member.userId(), "Real Member Name");
        UUID groupId = createGroup(admin, List.of(member.userId()));

        deleteMe(member.accessToken(), "secret123").andExpect(status().isNoContent());

        List<JsonNode> system = systemMessages(groupId, admin);
        List<String> systemText = system.stream().map(n -> n.get("content").asText()).toList();
        assertThat(systemText).anyMatch(text -> text.contains(DeletedAccountPolicy.DISPLAY_NAME));
        assertThat(systemText).noneMatch(text -> text.contains("Real Member Name"));
    }

    @Test
    void repeatedDeleteWithSameTokenReturnsTokenInvalid() throws Exception {
        Registered user = register("+84905553110", "secret123");
        deleteMe(user.accessToken(), "secret123").andExpect(status().isNoContent());

        deleteMe(user.accessToken(), "secret123")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void deletedUserResponseIncludesDeletedFlagAndOmitsPhone() throws Exception {
        Registered user = register("+84905553111", "secret123");
        Registered peer = register("+84905553112", "secret123");
        UUID conversationId = openDirect(user, peer);

        deleteMe(user.accessToken(), "secret123").andExpect(status().isNoContent());

        var directOpen = postJson("/api/v1/conversations/direct",
                Map.of("userId", user.userId().toString()), peer.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode membersNode = objectMapper.readTree(directOpen.getResponse().getContentAsString()).get("members");
        JsonNode member = null;
        for (JsonNode candidate : membersNode) {
            if (user.userId().toString().equals(candidate.get("userId").asText())) {
                member = candidate;
                break;
            }
        }
        assertThat(member).isNotNull();
        assertThat(member.get("deleted").asBoolean()).isTrue();
        assertThat(member.has("phone")).isFalse();

        var deletedUser = userRepository.findById(user.userId()).orElseThrow();
        JsonNode userResponse = objectMapper.valueToTree(UserResponse.from(deletedUser));
        assertThat(userResponse.get("deleted").asBoolean()).isTrue();
        assertThat(userResponse.has("phone")).isFalse();
    }

    @Test
    void refreshAfterDeletionIsRejected() throws Exception {
        Registered user = register("+84905553113", "secret123");
        String refreshToken = user.refreshToken();
        deleteMe(user.accessToken(), "secret123").andExpect(status().isNoContent());

        var refresh = postJson("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken))
                .andExpect(status().isUnauthorized())
                .andReturn();
        String code = objectMapper.readTree(refresh.getResponse().getContentAsString())
                .get("error").get("code").asText();
        assertThat(code).isIn("TOKEN_INVALID", "TOKEN_REUSED", "TOKEN_EXPIRED");
    }

    @Test
    void pendingJoinRequestRemovedOnDeletion() throws Exception {
        Registered owner = register("+84905553114", "secret123");
        Registered requester = register("+84905553115", "secret123");
        Registered placeholder = register("+84905553116", "secret123");
        UUID groupId = createGroup(owner, List.of(placeholder.userId()));
        String inviteToken = createInviteLink(owner, groupId);
        var join = postJson("/api/v1/invite-links/" + inviteToken + "/join", Map.of(), requester.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        UUID requestId = UUID.fromString(objectMapper.readTree(
                join.getResponse().getContentAsString()).get("requestId").asText());

        deleteMe(requester.accessToken(), "secret123").andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM group_join_requests WHERE user_id = ?",
                Integer.class, requester.userId())).isZero();
        postJson("/api/v1/conversations/" + groupId + "/join-requests/" + requestId + "/approve",
                Map.of(), owner.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Integer.class, groupId, requester.userId())).isZero();
    }

    @TestConfiguration
    static class PauseConfig {
        @Bean
        @Primary
        TestAccountDeletionPause testAccountDeletionPause() {
            return new TestAccountDeletionPause();
        }

        @Bean
        @Primary
        TestRefreshBoundaryProbe testRefreshBoundaryProbe() {
            return new TestRefreshBoundaryProbe();
        }
    }

    static final class TestRefreshBoundaryProbe implements RefreshReplayConfiguration.BoundaryHook {
        volatile Runnable beforeRotateUserLock = () -> {};

        void reset() {
            beforeRotateUserLock = () -> {};
        }

        @Override
        public void reached(RefreshReplayConfiguration.Boundary boundary,
                            RefreshReplayConfiguration.Operation operation,
                            UUID userId,
                            UUID tokenId) {
            if (boundary == RefreshReplayConfiguration.Boundary.BEFORE_USER_LOCK
                    && operation == RefreshReplayConfiguration.Operation.ROTATE) {
                beforeRotateUserLock.run();
            }
        }
    }

    static final class TestAccountDeletionPause implements AccountDeletionCoordination {
        volatile Runnable whenBeforeTombstone = () -> {};
        private volatile CountDownLatch resumeDeletion = new CountDownLatch(1);

        void reset() {
            whenBeforeTombstone = () -> {};
            resumeDeletion = new CountDownLatch(1);
        }

        void resume() {
            resumeDeletion.countDown();
        }

        void awaitResume() throws InterruptedException {
            resumeDeletion.await(30, TimeUnit.SECONDS);
        }

        @Override
        public void beforeTombstone(UUID userId) {
            whenBeforeTombstone.run();
        }
    }

    private void assertAccountDeletionWinsRace(Registered user, Callable<Integer> mutation)
            throws Exception {
        CountDownLatch deletionAtTombstone = new CountDownLatch(1);
        pause.whenBeforeTombstone = () -> {
            deletionAtTombstone.countDown();
            try {
                pause.awaitResume();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Integer> deletion = null;
        Future<Integer> inFlightMutation = null;
        boolean blockedOnUserLock = false;
        try {
            try {
                deletion = executor.submit(() -> deleteMe(user.accessToken(), "secret123")
                        .andReturn().getResponse().getStatus());
                assertThat(deletionAtTombstone.await(30, TimeUnit.SECONDS)).isTrue();

                inFlightMutation = executor.submit(mutation);
                try {
                    TestWaits.until("in-flight relationship write to wait on the deleting users row",
                            () -> userLockWaiterCount() > 0);
                    blockedOnUserLock = true;
                } catch (ConditionTimeoutException expectedOnTheUnfixedImplementation) {
                    // Keep the schedule deterministic on the unfixed code too:
                    // deletion is resumed below before observing its outcome.
                }
            } finally {
                pause.resume();
            }

            assertThat(deletion).isNotNull();
            assertThat(deletion.get(60, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(inFlightMutation).isNotNull();
            int mutationStatus = inFlightMutation.get(60, TimeUnit.SECONDS);
            assertThat(blockedOnUserLock)
                    .as("relationship creation must acquire the target user lock before other rows")
                    .isTrue();
            assertThat(mutationStatus)
                    .as("a mutation resumed after deletion must reject the tombstoned account")
                    .isEqualTo(404);
        } finally {
            pause.resume();
            executor.shutdownNow();
        }
    }

    private int userLockWaiterCount() {
        String url = environmentValue("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
        String username = environmentValue("IT_DB_USER", "nexachat");
        String password = environmentValue("IT_DB_PASSWORD", "nexachat");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*) FROM pg_stat_activity
                     WHERE datname = current_database()
                       AND pid <> pg_backend_pid()
                       AND wait_event_type = 'Lock'
                       AND query ILIKE ?
                     """)) {
            statement.setString(1, "%users%");
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Could not inspect the in-flight user-row lock", ex);
        }
    }

    private static String environmentValue(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private int uploadAttachment(Registered user) throws Exception {
        return mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "race.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.MvcResult uploadAvatar(Registered user) throws Exception {
        return mockMvc.perform(multipart("/api/v1/users/me/avatar")
                        .file(new MockMultipartFile("file", "avatar.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andReturn();
    }

    private int uploadAvatarStatus(Registered user) throws Exception {
        return uploadAvatar(user).getResponse().getStatus();
    }

    private Set<String> storedKeys(String subdirectory) throws IOException {
        Path directory = Path.of(storageRoot).resolve(subdirectory);
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .forEach(path -> keys.add(subdirectory + "/" + path.getFileName()));
        }
        return keys;
    }

    private static String storageKeyFromUrl(String url) {
        String prefix = "/api/v1/files/";
        if (url == null || !url.startsWith(prefix)) {
            throw new IllegalArgumentException("Unexpected storage URL");
        }
        return url.substring(prefix.length());
    }

    private ResultActions deleteMe(String accessToken, String password) throws Exception {
        return mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("password", password))));
    }

    private ResultActions patchJson(String path, Map<String, ?> body, String token) throws Exception {
        return mockMvc.perform(patch(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private UUID openDirect(Registered a, Registered b) throws Exception {
        var result = postJson("/api/v1/conversations/direct",
                Map.of("userId", b.userId().toString()), a.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createGroup(Registered creator, List<UUID> memberIds) throws Exception {
        var result = postJson("/api/v1/conversations/group",
                Map.of("title", "D4 " + UUID.randomUUID(), "memberIds",
                        memberIds.stream().map(UUID::toString).toList()),
                creator.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void setDisplayName(UUID userId, String name) {
        jdbcTemplate.update("UPDATE users SET display_name = ? WHERE id = ?", name, userId);
    }

    private int memberCount(UUID conversationId, UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Integer.class, conversationId, userId);
    }

    private List<JsonNode> systemMessages(UUID conversationId, Registered reader) throws Exception {
        var result = mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + reader.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        List<JsonNode> system = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            if ("SYSTEM".equals(item.get("type").asText())) {
                system.add(item);
            }
        }
        return system;
    }

    private String createInviteLink(Registered owner, UUID groupId) throws Exception {
        return createInviteLink(owner, groupId, true);
    }

    private String createInviteLink(Registered owner, UUID groupId, boolean requiresApproval) throws Exception {
        var result = postJson("/api/v1/conversations/" + groupId + "/invite-link",
                Map.of("requiresApproval", requiresApproval), owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
