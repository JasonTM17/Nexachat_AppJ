package com.nexachat.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.testing.SqlStatementCounter;
import com.nexachat.testing.SqlStatementCounter.Measurement;
import com.nexachat.testing.SqlStatementCounterTestConfiguration;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Postgres-backed perf and correctness guards: statement budgets on multi-
 * conversation sync, inbox keyset stability at tied activity timestamps, and
 * unread counts that ignore soft-deleted messages.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Import(SqlStatementCounterTestConfiguration.class)
class ServerHotPathPerfIT {

  @DynamicPropertySource
  static void datasourceProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url",
        () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
    registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
    registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private UserRepository userRepository;
  @Autowired
  private JwtService jwtService;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @Autowired
  private SqlStatementCounter sqlCounter;

  @Test
  void syncDeltaDoesNotRequeryMembershipPerWatermark() throws Exception {
    TestUser owner = createUser("Perf sync owner");
    List<UUID> conversationIds = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      TestUser peer = createUser("Perf peer " + i);
      conversationIds.add(openDirect(owner, peer));
      postJson("/api/v1/conversations/" + conversationIds.get(i) + "/messages", owner,
          Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "TEXT", "content", "Hi " + i))
          .andExpect(status().isCreated());
    }

    List<Map<String, Object>> watermarks = conversationIds.stream()
        .map(id -> Map.<String, Object>of("conversationId", id.toString(), "lastSeq", 0))
        .toList();
    mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(owner.token())))
        .andExpect(status().isOk());

    Measurement<MvcResult> measurement = sqlCounter.measure(
        () -> postJson("/api/v1/sync", owner, Map.of("watermarks", watermarks))
            .andExpect(status().isOk())
            .andReturn());

    // Eight conversations: batched flags (1) + per-conv reads — not 2× exists checks.
    assertThat(measurement.sqlExecutions())
        .as("sync with 8 watermarks should not issue per-conversation membership probes")
        .isLessThanOrEqualTo(130);
  }

  @Test
  void inboxPagingDoesNotSkipOrDuplicateWhenActivityTimestampsTie() throws Exception {
    TestUser owner = createUser("Perf inbox tie");
    Instant tiedCreatedAt = Instant.parse("2026-08-01T12:00:00Z");
    List<UUID> conversationIds = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      TestUser peer = createUser("Tie peer " + i);
      UUID conversationId = openDirect(owner, peer);
      postJson("/api/v1/conversations/" + conversationId + "/messages", owner,
          Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "TEXT", "content", "m"))
          .andExpect(status().isCreated());
      conversationIds.add(conversationId);
    }
    Timestamp ts = Timestamp.from(tiedCreatedAt);
    for (UUID conversationId : conversationIds) {
      jdbcTemplate.update("""
          UPDATE conversations
          SET created_at = ?, last_message_seq = 1
          WHERE id = ?
          """, ts, conversationId);
    }

    Set<UUID> seen = new HashSet<>();
    String cursor = null;
    for (int page = 0; page < 5; page++) {
      String url = cursor == null
          ? "/api/v1/conversations?limit=1"
          : "/api/v1/conversations?limit=1&cursor=" + cursor;
      MvcResult result = mockMvc.perform(get(url).header("Authorization", bearer(owner.token())))
          .andExpect(status().isOk())
          .andReturn();
      JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString());
      if (items.isEmpty()) {
        break;
      }
      UUID id = UUID.fromString(items.get(0).get("id").asText());
      assertThat(seen.add(id)).as("duplicate inbox row on tied timestamps").isTrue();
      cursor = result.getResponse().getHeader("X-Next-Cursor");
      if (cursor == null || cursor.isBlank()) {
        break;
      }
    }
    assertThat(seen).containsExactlyInAnyOrderElementsOf(conversationIds);
  }

  @Test
  void unreadCountIgnoresSoftDeletedMessages() throws Exception {
    TestUser alice = createUser("Perf unread alice");
    TestUser bob = createUser("Perf unread bob");
    UUID conversationId = openDirect(alice, bob);

    String firstId = sendText(conversationId, bob, "first");
    String secondId = sendText(conversationId, bob, "second");
    deleteMessage(conversationId, bob, firstId);

    MvcResult inbox = mockMvc.perform(get("/api/v1/conversations")
            .header("Authorization", bearer(alice.token())))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode items = objectMapper.readTree(inbox.getResponse().getContentAsString());
    JsonNode entry = null;
    for (JsonNode node : items) {
      if (conversationId.toString().equals(node.get("id").asText())) {
        entry = node;
        break;
      }
    }
    assertThat(entry).isNotNull();
    assertThat(entry.get("unreadCount").asLong())
        .as("deleted rows after the read watermark must not inflate unread badges")
        .isEqualTo(1);
    assertThat(secondId).isNotBlank();
  }

  private String sendText(UUID conversationId, TestUser sender, String content) throws Exception {
    MvcResult created = postJson("/api/v1/conversations/" + conversationId + "/messages", sender,
        Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "TEXT", "content", content))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
  }

  private void deleteMessage(UUID conversationId, TestUser sender, String messageId) throws Exception {
    mockMvc.perform(delete("/api/v1/conversations/" + conversationId + "/messages/" + messageId)
            .header("Authorization", bearer(sender.token())))
        .andExpect(status().isNoContent());
  }

  private TestUser createUser(String displayName) {
    String phone = "+849066" + String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000));
    User user = userRepository.saveAndFlush(User.create(phone, "x".repeat(60), displayName));
    return new TestUser(user.getId(), jwtService.generateAccessToken(user));
  }

  private UUID openDirect(TestUser owner, TestUser peer) throws Exception {
    MvcResult result = postJson("/api/v1/conversations/direct", owner,
        Map.of("userId", peer.userId().toString()))
        .andExpect(status().isCreated())
        .andReturn();
    return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText());
  }

  private org.springframework.test.web.servlet.ResultActions postJson(String path, TestUser user, Object body)
      throws Exception {
    return mockMvc.perform(post(path)
        .header("Authorization", bearer(user.token()))
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(body)));
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  private record TestUser(UUID userId, String token) {}
}
