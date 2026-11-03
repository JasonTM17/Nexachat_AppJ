package com.nexachat.contact;

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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the P4 slice (user search + contacts + direct
 * conversations) against a real PostgreSQL — same pattern as {@code AuthFlowIT}:
 * Flyway applies V1__init.sql on boot and the datasource comes from env vars
 * defaulting to the local docker-compose instance ({@code localhost:5433}).
 *
 * <p>Covers: exact-phone search + prefix search (caller excluded, params XOR),
 * add-contact 201 / dup 409 ALREADY_CONTACT / self 400 / unknown 404, alias
 * PATCH, idempotent DELETE 204, and direct-conversation idempotency —
 * both directions and repeat opens converge on ONE conversation row
 * (asserted via {@code direct_key} on the tables).
 *
 * <p>{@code PER_CLASS} lifecycle: the two users are registered once in
 * {@code @BeforeAll} and shared by all tests — deliberate, because the
 * auth register bucket (10 per 5 min per IP, see RateLimitInterceptor) is
 * shared across the whole suite context and 6 fresh registrations could
 * collectively exhaust it.
 *
 * <p>Note on the double-open: the two POSTs run SEQUENTIALLY here — MockMvc
 * cannot deterministically interleave two transactions, and the true race
 * (loser hits direct_key UNIQUE → re-reads winner) is proven by the
 * DataIntegrityViolation case in {@code ConversationServiceTest}. The
 * sequential run still proves the contract: two logical "open" calls yield a
 * single conversation row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContactConversationIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
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

    private String uniqueTag;
    private TestUser alice;
    private TestUser bob;
    private TestUser carol; // separate pair member for the concurrent-open test

    @BeforeAll
    void registerUsers() throws Exception {
        // Shared dev DB: purge leftovers from earlier IT runs (all IT phones
        // are +8490*, seeds are +849000*); conversations die before users
        // because created_by has no cascade.
        String like = "phone LIKE '+8490%' AND phone NOT LIKE '+849000%'";
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update("DELETE FROM users WHERE " + like);
        uniqueTag = "ITC" + System.nanoTime();
        // Display names START with the tag so q=<tag> prefix-matches them.
        alice = registerUser(uniqueTag + " Searcher");
        bob = registerUser(uniqueTag + " Target");
        // "Z…" prefix keeps her OUT of the q=uniqueTag prefix-search result.
        carol = registerUser("Zz" + uniqueTag + " Third");
    }

    /**
     * Random-but-plausible VN mobile in E.164 ({@code +8490} + 7 digits, first
     * non-zero) so re-runs never collide with rows committed by earlier runs
     * on the shared dev database.
     */
    private static String randomPhone() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "+8490" + (1 + random.nextInt(9)) + String.format("%06d", random.nextInt(1_000_000));
    }

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = randomPhone();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(
                UUID.fromString(body.get("user").get("id").asText()),
                body.get("user").get("phone").asText(),
                displayName,
                body.get("accessToken").asText());
    }

    private ResultActions getJson(String path, String accessToken) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + accessToken));
    }

    private ResultActions postJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    // ---------------------------------------------------------------- search

    @Test
    void userSearch_phoneExactAndPrefix() throws Exception {
        // Exact E.164 lookup → list of exactly one.
        mockMvc.perform(get("/api/v1/users/search")
                        .param("phone", bob.phone())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bob.id().toString()))
                .andExpect(jsonPath("$[0].phone").value(bob.phone()));

        // Prefix search on the unique displayName tag → bob found, caller excluded.
        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", uniqueTag)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bob.id().toString()));

        // Neither param / both params → 400 VALIDATION_FAILED.
        getJson("/api/v1/users/search", alice.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/users/search")
                        .param("phone", bob.phone())
                        .param("q", "x")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // Unknown phone → empty list, not 404.
        mockMvc.perform(get("/api/v1/users/search")
                        .param("phone", "+84909999999")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // -------------------------------------------------------------- contacts

    @Test
    void contactsLifecycle() throws Exception {
        // Add by phone → 201 with the resolved user fields.
        postJson("/api/v1/contacts", alice.accessToken(), Map.of("phone", bob.phone()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contactUserId").value(bob.id().toString()))
                .andExpect(jsonPath("$.phone").value(bob.phone()))
                .andExpect(jsonPath("$.displayName").value(bob.displayName()))
                .andExpect(jsonPath("$.aliasName").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isString());

        // Duplicate add → 409 ALREADY_CONTACT.
        postJson("/api/v1/contacts", alice.accessToken(), Map.of("phone", bob.phone()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_CONTACT"));

        // Self-add → 400 VALIDATION_FAILED.
        postJson("/api/v1/contacts", alice.accessToken(), Map.of("phone", alice.phone()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // Unknown phone → 404 USER_NOT_FOUND.
        postJson("/api/v1/contacts", alice.accessToken(), Map.of("phone", "+84909999998"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

        // List → one row with the friend's display name.
        getJson("/api/v1/contacts", alice.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].contactUserId").value(bob.id().toString()))
                .andExpect(jsonPath("$[0].displayName").value(bob.displayName()));

        // Alias set → 200 with alias; cleared again via JSON null.
        mockMvc.perform(patch("/api/v1/contacts/" + bob.id())
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("aliasName", "Bestie"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aliasName").value("Bestie"));
        mockMvc.perform(patch("/api/v1/contacts/" + bob.id())
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aliasName\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aliasName").doesNotExist());

        // Delete → 204; second delete still 204 (idempotent); list now empty.
        mockMvc.perform(delete("/api/v1/contacts/" + bob.id())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/contacts/" + bob.id())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNoContent());
        getJson("/api/v1/contacts", alice.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ------------------------------------------------- direct conversations

    @Test
    void directConversationIsIdempotentBothDirections() throws Exception {
        // A opens by userId → 201 with both members.
        MvcResult opened = postJson("/api/v1/conversations/direct", alice.accessToken(),
                Map.of("userId", bob.id().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DIRECT"))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andReturn();
        UUID conversationId = UUID.fromString(objectMapper
                .readTree(opened.getResponse().getContentAsString())
                .get("id").asText());

        // Same call again → 200, same id.
        postJson("/api/v1/conversations/direct", alice.accessToken(),
                Map.of("userId", bob.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversationId.toString()));

        // The OTHER direction (B → A) → same id — direct_key is order-independent.
        postJson("/api/v1/conversations/direct", bob.accessToken(),
                Map.of("userId", alice.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversationId.toString()));

        // By-phone form also converges on the same conversation.
        postJson("/api/v1/conversations/direct", alice.accessToken(),
                Map.of("phone", bob.phone()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversationId.toString()));

        // Sequential double-open (parallelism note in class javadoc): every
        // logical open collapsed onto ONE row, with exactly two member rows.
        Integer conversations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE direct_key = ?",
                Integer.class,
                directKey(alice.id(), bob.id()));
        assertThat(conversations).isEqualTo(1);
        Integer members = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?",
                Integer.class, conversationId);
        assertThat(members).isEqualTo(2);

        // Validation matrix: both params / self / unknown userId.
        postJson("/api/v1/conversations/direct", alice.accessToken(),
                Map.of("userId", bob.id().toString(), "phone", bob.phone()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        postJson("/api/v1/conversations/direct", alice.accessToken(),
                Map.of("userId", alice.id().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        postJson("/api/v1/conversations/direct", alice.accessToken(),
                Map.of("userId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    /**
     * Spec'd race case (phase-06: "bắn đồng thời 2 request → 1 row"): two
     * parallel opens of the SAME pair must converge on one conversation row —
     * the loser hits the direct_key UNIQUE violation and re-reads the winner.
     */
    @Test
    void concurrentDirectOpenYieldsSingleRow() throws Exception {
        // carol↔alice — a pair untouched by the other tests so the 201-vs-200
        // assertions elsewhere stay order-independent.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> open = () -> {
                MvcResult result = mockMvc.perform(post("/api/v1/conversations/direct")
                                .header("Authorization", "Bearer " + alice.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("userId", carol.id().toString()))))
                        .andReturn();
                return objectMapper.readTree(result.getResponse().getContentAsString())
                        .get("id").asText();
            };
            Future<String> first = pool.submit(open);
            Future<String> second = pool.submit(open);

            String id1 = first.get(30, TimeUnit.SECONDS);
            String id2 = second.get(30, TimeUnit.SECONDS);
            assertThat(id2).isEqualTo(id1);
        } finally {
            pool.shutdownNow();
        }

        Integer conversations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversations WHERE direct_key = ?",
                Integer.class,
                directKey(alice.id(), carol.id()));
        assertThat(conversations).isEqualTo(1);
    }

    /** Mirrors {@code ConversationService.directKey} — LEAST:GREATEST on strings. */
    private static String directKey(UUID a, UUID b) {
        String first = a.toString();
        String second = b.toString();
        return first.compareTo(second) < 0 ? first + ':' + second : second + ':' + first;
    }
}
