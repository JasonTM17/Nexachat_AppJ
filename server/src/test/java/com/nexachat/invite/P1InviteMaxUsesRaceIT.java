package com.nexachat.invite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adversarial review repro: two direct joins racing past the same link load can
 * both satisfy {@code useCount < maxUses} because the conversation row lock only
 * serializes the member-cap section; the link row is read (and cached in the
 * persistence context) before the lock. With maxUses=1 at most one joiner may
 * be admitted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P1InviteMaxUsesRaceIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser dave;
    private TestUser bob;
    private TestUser carol;

    @BeforeAll
    void setUp() {
        alice = createTestUser("Race Alice");
        dave = createTestUser("Race Dave");
        bob = createTestUser("Race Bob");
        carol = createTestUser("Race Carol");
    }

    @Test
    void concurrentDirectJoinsCannotExceedMaxUses() throws Exception {
        UUID groupId = createGroup(List.of(dave.id()));
        MvcResult link = postJson("/api/v1/conversations/" + groupId + "/invite-link",
                alice.accessToken(), Map.of("requiresApproval", false, "maxUses", 1))
                .andExpect(res -> assertThat(res.getResponse().getStatus()).isEqualTo(201))
                .andReturn();
        String token = objectMapper.readTree(link.getResponse().getContentAsString())
                .get("token").asText();
        UUID linkId = UUID.fromString(objectMapper.readTree(link.getResponse().getContentAsString())
                .get("id").asText());

        // Hold the conversation row so both join transactions load the link
        // (useCount=0), then block at the conversation lock. Releasing it makes
        // the two joins run strictly one after the other against stale state.
        try (Connection lockConnection = jdbcTemplate.getDataSource().getConnection()) {
            lockConnection.setAutoCommit(false);
            try (PreparedStatement statement = lockConnection.prepareStatement(
                    "SELECT 1 FROM conversations WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, groupId);
                statement.executeQuery().close();
            }

            CompletableFuture<Integer> first = joinAsync(token, bob.accessToken());
            CompletableFuture<Integer> second = joinAsync(token, carol.accessToken());
            Thread.sleep(1200);
            lockConnection.commit();

            int firstStatus = first.get(10, TimeUnit.SECONDS);
            int secondStatus = second.get(10, TimeUnit.SECONDS);

            Integer members = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?",
                    Integer.class, groupId);
            Integer useCount = jdbcTemplate.queryForObject(
                    "SELECT use_count FROM group_invite_links WHERE id = ?", Integer.class, linkId);

            assertThat(List.of(firstStatus, secondStatus)).containsOnly(200, 404);
            assertThat(members).isEqualTo(3);
            assertThat(useCount).isEqualTo(1);
        }
    }

    private CompletableFuture<Integer> joinAsync(String token, String accessToken) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(post("/api/v1/invite-links/" + token + "/join")
                                .header("Authorization", "Bearer " + accessToken))
                        .andReturn().getResponse().getStatus();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    @Test
    void concurrentRevocationMustInvalidateAnInFlightJoin() throws Exception {
        UUID groupId = createGroup(List.of(dave.id()));
        MvcResult link = postJson("/api/v1/conversations/" + groupId + "/invite-link",
                alice.accessToken(), Map.of("requiresApproval", false))
                .andExpect(res -> assertThat(res.getResponse().getStatus()).isEqualTo(201))
                .andReturn();
        String token = objectMapper.readTree(link.getResponse().getContentAsString())
                .get("token").asText();
        UUID linkId = UUID.fromString(objectMapper.readTree(link.getResponse().getContentAsString())
                .get("id").asText());

        try (Connection lockConnection = jdbcTemplate.getDataSource().getConnection()) {
            lockConnection.setAutoCommit(false);
            try (PreparedStatement statement = lockConnection.prepareStatement(
                    "SELECT 1 FROM conversations WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, groupId);
                statement.executeQuery().close();
            }

            CompletableFuture<Integer> join = joinAsync(token, bob.accessToken());
            Thread.sleep(800);

            // Revoke while the join is parked at the conversation lock with the
            // link entity already loaded inside its persistence context.
            mockMvc.perform(delete("/api/v1/conversations/{id}/invite-link", groupId)
                            .header("Authorization", "Bearer " + alice.accessToken()))
                    .andExpect(status().isNoContent());

            lockConnection.commit();
            int joinStatus = join.get(10, TimeUnit.SECONDS);

            Integer membership = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                    Integer.class, groupId, bob.id());
            Integer revokedAt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM group_invite_links WHERE id = ? AND revoked_at IS NOT NULL",
                    Integer.class, linkId);

            assertThat(revokedAt).as("the revocation must survive the in-flight join").isEqualTo(1);
            assertThat(joinStatus).isEqualTo(404);
            assertThat(membership).isZero();
        }
    }

    @Test
    void concurrentRotationsMustNotReturnAServerError() throws Exception {
        UUID groupId = createGroup(List.of(dave.id()));
        MvcResult link = postJson("/api/v1/conversations/" + groupId + "/invite-link",
                alice.accessToken(), Map.of("requiresApproval", false))
                .andExpect(res -> assertThat(res.getResponse().getStatus()).isEqualTo(201))
                .andReturn();
        UUID linkId = UUID.fromString(objectMapper.readTree(link.getResponse().getContentAsString())
                .get("id").asText());

        try (Connection lockConnection = jdbcTemplate.getDataSource().getConnection()) {
            lockConnection.setAutoCommit(false);
            try (PreparedStatement statement = lockConnection.prepareStatement(
                    "SELECT 1 FROM group_invite_links WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, linkId);
                statement.executeQuery().close();
            }

            // Both rotations read the same active link, then park on its row
            // lock. Releasing them makes both try to insert a new active link;
            // the partial unique index admits only one.
            CompletableFuture<Integer> first = rotateAsync(groupId);
            CompletableFuture<Integer> second = rotateAsync(groupId);
            Thread.sleep(1200);
            lockConnection.commit();

            int firstStatus = first.get(10, TimeUnit.SECONDS);
            int secondStatus = second.get(10, TimeUnit.SECONDS);

            assertThat(List.of(firstStatus, secondStatus))
                    .as("concurrent rotation must produce a defined outcome, never a 5xx")
                    .doesNotContain(500);
        }
    }

    private CompletableFuture<Integer> rotateAsync(UUID groupId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(post("/api/v1/conversations/{id}/invite-link", groupId)
                                .header("Authorization", "Bearer " + alice.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requiresApproval\":false}"))
                        .andReturn().getResponse().getStatus();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private TestUser createTestUser(String displayName) {
        String phone = "+8494" + String.format("%07d",
                ThreadLocalRandom.current().nextInt(10_000_000));
        User user = User.create(phone, "hash", displayName);
        user = userRepository.saveAndFlush(user);
        return new TestUser(user.getId(), jwtService.generateAccessToken(user));
    }

    private UUID createGroup(List<UUID> memberIds) throws Exception {
        List<String> ids = memberIds.stream().map(UUID::toString).toList();
        MvcResult result = postJson("/api/v1/conversations/group", alice.accessToken(),
                Map.of("title", "MaxUses race guard", "memberIds", ids))
                .andExpect(res -> assertThat(res.getResponse().getStatus()).isEqualTo(201))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions postJson(
            String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body != null ? objectMapper.writeValueAsString(body) : "{}"));
    }
}
