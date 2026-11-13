package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Retries account deletion after rollback at representative persistence boundaries. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Testcontainers
class AccountDeletionFailureRecoveryIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("nexachat_deletion_recovery_it")
            .withUsername("nexachat")
            .withPassword("nexachat");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("nexachat.security.cors-allowed-origins", () -> "https://app.example.com");
        registry.add("nexachat.security.admin-phones", () -> "");
        registry.add("nexachat.storage.root", () -> System.getProperty("java.io.tmpdir") + "/nexachat-deletion-recovery-it");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE users CASCADE");
    }

    @Test
    void retriesAfterAttachmentCleanupFailureAndPreservesPeerHistory() throws Exception {
        Fixture fixture = fixture("+84909951001", "+84909951002");
        String key = "attachments/" + UUID.randomUUID() + ".txt";
        jdbc.update("""
                INSERT INTO message_attachments (owner_id, storage_key, original_name, mime_type, size_bytes)
                VALUES (?, ?, 'retry.txt', 'text/plain', 4)
                """, fixture.owner().id(), key);

        assertFailureAndRetry(fixture, "message_attachments", "DELETE");

        assertThat(count("SELECT count(*) FROM message_attachments WHERE storage_key = ?", key)).isZero();
        JsonNode history = history(fixture);
        assertThat(history.path("items")).hasSize(1);
        assertThat(history.path("items").get(0).path("content").asText()).isEqualTo("kept history");
        assertThat(history.path("items").get(0).path("senderDisplayName").asText()).isEqualTo("Deleted account");
    }

    @Test
    void retriesAfterDeviceTokenCleanupFailureWithoutPartialScrub() throws Exception {
        Fixture fixture = fixture("+84909952001", "+84909952002");
        jdbc.update("""
                INSERT INTO user_device_tokens (user_id, token, platform)
                VALUES (?, ?, 'ANDROID')
                """, fixture.owner().id(), "synthetic-device-token");

        assertFailureAndRetry(fixture, "user_device_tokens", "DELETE");

        assertThat(count("SELECT count(*) FROM user_device_tokens WHERE user_id = ?", fixture.owner().id())).isZero();
        assertThat(activeSessions(fixture.owner().id())).isZero();
        assertThat(userPhone(fixture.owner().id())).isNull();
        assertThat(history(fixture).path("items").get(0).path("content").asText()).isEqualTo("kept history");
    }

    @Test
    void retriesAfterRefreshSessionRevocationFailureWithoutTombstoning() throws Exception {
        Fixture fixture = fixture("+84909953001", "+84909953002");

        assertFailureAndRetry(fixture, "refresh_tokens", "UPDATE");

        assertThat(activeSessions(fixture.owner().id())).isZero();
        assertThat(userPhone(fixture.owner().id())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE conversation_id = ?",
                Integer.class, fixture.conversationId())).isEqualTo(1);
    }

    @Test
    void retryAfterTombstoneWriteFailureKeepsOriginalPiiUntilSuccessfulRetry() throws Exception {
        Fixture fixture = fixture("+84909954001", "+84909954002");
        installTombstoneFailure();
        try {
            delete(fixture.owner()).andExpect(status().is5xxServerError());
            assertThat(userPhone(fixture.owner().id())).isEqualTo(fixture.phone());
            assertThat(activeSessions(fixture.owner().id())).isEqualTo(1);
            mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(fixture.owner())))
                    .andExpect(status().isOk());
        } finally {
            dropFailureTrigger();
        }

        delete(fixture.owner()).andExpect(status().isNoContent());
        assertDeleted(fixture);
    }

    private void assertFailureAndRetry(Fixture fixture, String table, String operation) throws Exception {
        installFailure(table, operation);
        try {
            delete(fixture.owner()).andExpect(status().is5xxServerError());
            assertThat(userPhone(fixture.owner().id())).as("the failed transaction must roll back tombstoning")
                    .isEqualTo(fixture.phone());
            assertThat(activeSessions(fixture.owner().id())).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM messages WHERE conversation_id = ?", fixture.conversationId()))
                    .isEqualTo(1);
            mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(fixture.owner())))
                    .andExpect(status().isOk());
        } finally {
            dropFailureTrigger();
        }

        delete(fixture.owner()).andExpect(status().isNoContent());
        assertDeleted(fixture);
    }

    private void installFailure(String table, String operation) {
        if (!Set.of("message_attachments:DELETE", "user_device_tokens:DELETE", "refresh_tokens:UPDATE")
                .contains(table + ":" + operation)) {
            throw new IllegalArgumentException("Unsupported failure boundary");
        }
        jdbc.execute("""
                CREATE FUNCTION fail_deletion_recovery_boundary() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected deletion boundary failure'; END
                $$
                """);
        jdbc.execute("CREATE TRIGGER fail_deletion_recovery_boundary BEFORE " + operation + " ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION fail_deletion_recovery_boundary()");
    }

    private void installTombstoneFailure() {
        jdbc.execute("""
                CREATE FUNCTION fail_deletion_recovery_tombstone() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected tombstone failure'; END
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_deletion_recovery_tombstone AFTER UPDATE OF deleted_at ON users
                FOR EACH ROW WHEN (NEW.deleted_at IS NOT NULL)
                EXECUTE FUNCTION fail_deletion_recovery_tombstone()
                """);
    }

    private void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_deletion_recovery_boundary ON message_attachments");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_deletion_recovery_boundary ON user_device_tokens");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_deletion_recovery_boundary ON refresh_tokens");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_deletion_recovery_boundary()");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_deletion_recovery_tombstone ON users");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_deletion_recovery_tombstone()");
    }

    private Fixture fixture(String phone, String peerPhone) throws Exception {
        Registered owner = register(phone);
        Registered peer = register(peerPhone);
        UUID conversationId = UUID.randomUUID();
        jdbc.update("INSERT INTO conversations (id, type, direct_key, created_by) VALUES (?, 'DIRECT', ?, ?)",
                conversationId, "deletion-recovery-" + conversationId, owner.id());
        jdbc.update("INSERT INTO conversation_members (conversation_id, user_id, role) VALUES (?, ?, 'MEMBER'), (?, ?, 'MEMBER')",
                conversationId, owner.id(), conversationId, peer.id());
        jdbc.update("""
                INSERT INTO messages (conversation_id, sender_id, client_message_id, type, content)
                VALUES (?, ?, ?, 'TEXT', 'kept history')
                """, conversationId, owner.id(), UUID.randomUUID());
        return new Fixture(owner, peer, phone, conversationId);
    }

    private Registered register(String phone) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", "Recovery fixture"))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return new Registered(UUID.fromString(body.path("user").path("id").asText()),
                body.path("accessToken").asText(), body.path("refreshToken").asText());
    }

    private org.springframework.test.web.servlet.ResultActions delete(Registered user) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/users/me").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"secret123\"}"));
    }

    private JsonNode history(Fixture fixture) throws Exception {
        var response = mvc.perform(get("/api/v1/conversations/" + fixture.conversationId() + "/messages")
                        .header("Authorization", bearer(fixture.peer())))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString());
    }

    private void assertDeleted(Fixture fixture) throws Exception {
        assertThat(userPhone(fixture.owner().id())).isNull();
        assertThat(count("SELECT count(*) FROM users WHERE id = ? AND display_name = 'Deleted account'",
                fixture.owner().id())).isEqualTo(1);
        assertThat(activeSessions(fixture.owner().id())).isZero();
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(fixture.owner())))
                .andExpect(status().isUnauthorized());
    }

    private int activeSessions(UUID userId) {
        return count("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    private String userPhone(UUID userId) {
        return jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, userId);
    }

    private int count(String sql, Object value) {
        return jdbc.queryForObject(sql, Integer.class, value);
    }

    private static String bearer(Registered user) {
        return "Bearer " + user.accessToken();
    }

    private record Registered(UUID id, String accessToken, String refreshToken) { }
    private record Fixture(Registered owner, Registered peer, String phone, UUID conversationId) { }
}
