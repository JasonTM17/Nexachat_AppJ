package com.nexachat.invite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupInviteFlowIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        // Set member cap to 5 for testing cap enforcement
        registry.add("nexachat.group.max-members", () -> "5");
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
    private com.nexachat.user.UserRepository userRepository;
    @Autowired
    private com.nexachat.auth.JwtService jwtService;

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser alice; // admin
    private TestUser bob;   // initial member
    private TestUser charlie;
    private TestUser dave;
    private TestUser eve;

    @BeforeAll
    void setupFixtures() throws Exception {
        String like = "phone LIKE '+8497%'";
        jdbcTemplate.update("DELETE FROM group_invite_bans WHERE conversation_id IN "
                + "(SELECT id FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + "))");
        jdbcTemplate.update("DELETE FROM group_join_requests WHERE conversation_id IN "
                + "(SELECT id FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + "))");
        jdbcTemplate.update("DELETE FROM group_invite_links WHERE conversation_id IN "
                + "(SELECT id FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + "))");
        jdbcTemplate.update("DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update("DELETE FROM users WHERE " + like);

        String tag = "INV" + System.nanoTime();
        alice = createTestUser(tag + " Alice");
        bob = createTestUser(tag + " Bob");
        charlie = createTestUser(tag + " Charlie");
        dave = createTestUser(tag + " Dave");
        eve = createTestUser(tag + " Eve");
    }

    private static String randomPhone() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "+8497" + String.format("%07d", random.nextInt(10_000_000));
    }

    private TestUser createTestUser(String displayName) {
        String phone = randomPhone();
        com.nexachat.user.User user = com.nexachat.user.User.create(phone, "hash", displayName);
        user = userRepository.saveAndFlush(user);
        String token = jwtService.generateAccessToken(user);
        return new TestUser(user.getId(), phone, displayName, token);
    }

    private ResultActions getJson(String path, String accessToken) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + accessToken));
    }

    private ResultActions postJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body != null ? objectMapper.writeValueAsString(body) : "{}"));
    }

    private ResultActions patchJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(patch(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body != null ? objectMapper.writeValueAsString(body) : "{}"));
    }

    private ResultActions deleteJson(String path, String accessToken) throws Exception {
        return mockMvc.perform(delete(path).header("Authorization", "Bearer " + accessToken));
    }

    private UUID createGroup(TestUser creator, String title, List<UUID> memberIds) throws Exception {
        List<String> ids = memberIds.stream().map(UUID::toString).toList();
        MvcResult result = postJson("/api/v1/conversations/group", creator.accessToken(),
                Map.of("title", title, "memberIds", ids))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID createDirect(TestUser creator, UUID targetUserId) throws Exception {
        MvcResult result = postJson("/api/v1/conversations/direct", creator.accessToken(),
                Map.of("userId", targetUserId.toString()))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    // =========================================================================
    // End-to-End Invite Link & Approval Flow
    // =========================================================================

    @Test
    void endToEndInviteLinkAndApprovalFlow() throws Exception {
        // 1. Direct conversations return NOT_A_GROUP
        UUID directId = createDirect(alice, bob.id());
        postJson("/api/v1/conversations/" + directId + "/invite-link", alice.accessToken(), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NOT_A_GROUP"));

        // 2. Create Group with Alice (ADMIN) and Bob (MEMBER)
        UUID groupId = createGroup(alice, "Team Alpha", List.of(bob.id()));

        // 3. Non-admin Bob and non-member Charlie cannot manage invite link
        postJson("/api/v1/conversations/" + groupId + "/invite-link", bob.accessToken(), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));

        postJson("/api/v1/conversations/" + groupId + "/invite-link", charlie.accessToken(), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));

        // 4. Alice creates invite link with maxUses=2, requiresApproval=false
        MvcResult linkResult = postJson("/api/v1/conversations/" + groupId + "/invite-link", alice.accessToken(),
                Map.of("maxUses", 2, "requiresApproval", false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.requiresApproval").value(false))
                .andExpect(jsonPath("$.maxUses").value(2))
                .andReturn();

        String token1 = objectMapper.readTree(linkResult.getResponse().getContentAsString()).get("token").asText();
        assertThat(token1).isNotBlank();

        // 5. Charlie resolves safe preview (never leaks member list)
        MvcResult previewResult = getJson("/api/v1/invite-links/" + token1, charlie.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(groupId.toString()))
                .andExpect(jsonPath("$.title").value("Team Alpha"))
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.requiresApproval").value(false))
                .andExpect(jsonPath("$.isMember").value(false))
                .andReturn();

        // Assert member lists are never in the preview payload
        JsonNode previewNode = objectMapper.readTree(previewResult.getResponse().getContentAsString());
        assertThat(previewNode.has("members")).isFalse();
        assertThat(previewNode.has("memberIds")).isFalse();

        // 6. Charlie joins without approval -> status JOINED
        postJson("/api/v1/invite-links/" + token1 + "/join", charlie.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("JOINED"))
                .andExpect(jsonPath("$.conversationId").value(groupId.toString()));

        // Idempotent join: Charlie calls join again
        postJson("/api/v1/invite-links/" + token1 + "/join", charlie.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_MEMBER"));

        // 7. Dave joins without approval -> useCount reaches 2 (maxUses)
        postJson("/api/v1/invite-links/" + token1 + "/join", dave.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("JOINED"));

        // 8. Eve tries to resolve or join using the used-up link -> uniform 404 NOT_FOUND
        getJson("/api/v1/invite-links/" + token1, eve.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        postJson("/api/v1/invite-links/" + token1 + "/join", eve.accessToken(), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        // 9. Alice rotates invite link with requiresApproval=true
        MvcResult rotateResult = postJson("/api/v1/conversations/" + groupId + "/invite-link", alice.accessToken(),
                Map.of("requiresApproval", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requiresApproval").value(true))
                .andReturn();

        String token2 = objectMapper.readTree(rotateResult.getResponse().getContentAsString()).get("token").asText();
        assertThat(token2).isNotEqualTo(token1);

        // 10. Eve resolves new link
        getJson("/api/v1/invite-links/" + token2, eve.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(4))
                .andExpect(jsonPath("$.requiresApproval").value(true));

        // 11. Eve requests to join -> status PENDING (201 CREATED)
        MvcResult joinReqResult = postJson("/api/v1/invite-links/" + token2 + "/join", eve.accessToken(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestId").isString())
                .andReturn();

        String reqId = objectMapper.readTree(joinReqResult.getResponse().getContentAsString()).get("requestId").asText();

        // Eve calls join again -> idempotent 200 OK PENDING
        postJson("/api/v1/invite-links/" + token2 + "/join", eve.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestId").value(reqId));

        // Requester sees status
        getJson("/api/v1/conversations/" + groupId + "/join-requests/me", eve.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.id").value(reqId));

        // 12. Alice lists join requests
        getJson("/api/v1/conversations/" + groupId + "/join-requests", alice.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(reqId))
                .andExpect(jsonPath("$[0].user.displayName").value(eve.displayName()));

        // 13. Alice approves Eve
        postJson("/api/v1/conversations/" + groupId + "/join-requests/" + reqId + "/approve", alice.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // Eve is now a member (now 5 members: Alice, Bob, Charlie, Dave, Eve — at member cap 5)
        getJson("/api/v1/conversations/" + groupId + "/join-requests/me", eve.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 14. Member cap reached (cap=5): New user Frank tries to join
        TestUser frank = createTestUser("Frank " + System.nanoTime());
        // Toggle approval off for test
        patchJson("/api/v1/conversations/" + groupId + "/invite-link", alice.accessToken(),
                Map.of("requiresApproval", false))
                .andExpect(status().isOk());

        postJson("/api/v1/invite-links/" + token2 + "/join", frank.accessToken(), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.limit").value(5));

        // 15. Alice removes Charlie from group
        deleteJson("/api/v1/conversations/" + groupId + "/members/" + charlie.id(), alice.accessToken())
                .andExpect(status().isNoContent());

        // Charlie tries to rejoin via the same invite link -> 403 FORBIDDEN (banned)
        postJson("/api/v1/invite-links/" + token2 + "/join", charlie.accessToken(), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // 16. Alice revokes the invite link
        deleteJson("/api/v1/conversations/" + groupId + "/invite-link", alice.accessToken())
                .andExpect(status().isNoContent());

        // Revoked link returns uniform 404 NOT_FOUND
        getJson("/api/v1/invite-links/" + token2, dave.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // =========================================================================
    // Concurrent joins at member cap
    // =========================================================================

    @Test
    void concurrentJoinsAtMemberCap_handledSafely() throws Exception {
        // Create new group with Alice (1 member), cap is 5 (so only 4 can join)
        TestUser admin = createTestUser("AdminCap " + System.nanoTime());
        TestUser initial = createTestUser("Initial " + System.nanoTime());
        UUID groupId = createGroup(admin, "Cap Test Group", List.of(initial.id())); // 2 members now

        // Create open invite link (requiresApproval=false)
        MvcResult linkResult = postJson("/api/v1/conversations/" + groupId + "/invite-link", admin.accessToken(),
                Map.of("requiresApproval", false))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(linkResult.getResponse().getContentAsString()).get("token").asText();

        // We prepare 6 users attempting to join concurrently. Since cap is 5 and 2 members exist,
        // exactly 3 joins must succeed (reaching 5 members), and remaining must be rejected with 400 VALIDATION_FAILED.
        List<TestUser> joiners = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            joiners.add(createTestUser("Joiner" + i + "_" + System.nanoTime()));
        }

        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch readyLatch = new CountDownLatch(6);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        List<Future<Void>> futures = new ArrayList<>();
        for (TestUser joiner : joiners) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                try {
                    MvcResult res = postJson("/api/v1/invite-links/" + token + "/join", joiner.accessToken(), null)
                            .andReturn();
                    int code = res.getResponse().getStatus();
                    if (code == 200) {
                        successCount.incrementAndGet();
                    } else if (code == 400) {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // unexpected
                }
                return null;
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release all threads concurrently

        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Invariant: total members in group must never exceed cap (5)
        Integer finalMemberCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?",
                Integer.class, groupId);

        assertThat(finalMemberCount).isEqualTo(5);
        assertThat(successCount.get()).isEqualTo(3);
        assertThat(rejectedCount.get()).isEqualTo(3);
    }
}
