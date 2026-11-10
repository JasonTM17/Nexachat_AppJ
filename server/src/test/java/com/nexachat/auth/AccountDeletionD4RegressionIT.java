package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.attachment.StorageService;
import com.nexachat.user.AccountDeletionService;
import com.nexachat.user.AccountDeletionCoordination;
import com.nexachat.user.DeletedAccountPolicy;
import com.nexachat.user.UserRepository;
import com.nexachat.user.dto.UserResponse;
import com.nexachat.ws.StompAuthChannelInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private AccountDeletionService accountDeletionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetPause() {
        pause.reset();
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
        var result = postJson("/api/v1/conversations/" + groupId + "/invite-link",
                Map.of("requiresApproval", true), owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
