package com.nexachat.contact;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContactConversationIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
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
        ItPhoneRanges.clean(jdbcTemplate, ContactConversationIT.class);
        uniqueTag = "ITC" + System.nanoTime();
        // Display names START with the tag so q=<tag> prefix-matches them.
        alice = registerUser(uniqueTag + " Searcher");
        bob = registerUser(uniqueTag + " Target");
        // "Z…" prefix keeps her OUT of the q=uniqueTag prefix-search result.
        carol = registerUser("Zz" + uniqueTag + " Third");
    }

    /**
     * Next phone in this suite's {@link com.nexachat.testutil.ItPhoneRanges} range,
     * so re-runs never collide with rows committed by another suite.
     */
    private static String randomPhone() {
        return ItPhoneRanges.nextPhone(ContactConversationIT.class);
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
    @Order(1)
    void userSearch_phoneExactAndPrefix() throws Exception {
        // Exact E.164 lookup → list of exactly one.
        mockMvc.perform(get("/api/v1/users/search")
                        .param("phone", bob.phone())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bob.id().toString()))
                .andExpect(jsonPath("$[0].phone").value(bob.phone()));

        // Prefix search on the unique displayName tag → bob found, caller excluded; phone hidden.
        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", uniqueTag)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bob.id().toString()))
                .andExpect(jsonPath("$[0].phone").doesNotExist());

        // Valid E.164 that is not bob's exact number → empty list (no prefix match).
        // Fixture phones are allocated sequentially, so changing the last digit can land on
        // another fixture user (alice). Change the thousands digit: no fixture is that close.
        int index = bob.phone().length() - 4;
        char digit = bob.phone().charAt(index);
        char changed = digit == '0' ? '1' : '0';
        String nearMiss = bob.phone().substring(0, index) + changed + bob.phone().substring(index + 1);
        mockMvc.perform(get("/api/v1/users/search")
                        .param("phone", nearMiss)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

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

    @Test
    @Order(2)
    void userSearch_displayNameRevealsPhoneForExistingContactOnly() throws Exception {
        mockMvc.perform(delete("/api/v1/contacts/" + bob.id())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", uniqueTag)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].phone").doesNotExist());

        postJson("/api/v1/contacts", alice.accessToken(), Map.of("phone", bob.phone()))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", uniqueTag)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].phone").value(bob.phone()));

        mockMvc.perform(delete("/api/v1/contacts/" + bob.id())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNoContent());
    }

    // -------------------------------------------------------------- contacts

    @Test
    @Order(3)
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

    @Test
    @Order(4)
    void nonOwnerCannotPatchAnotherUsersContactAlias() throws Exception {
        postJson("/api/v1/contacts", alice.accessToken(), Map.of("phone", bob.phone()))
                .andExpect(status().isCreated());
        postJson("/api/v1/contacts", carol.accessToken(), Map.of("phone", alice.phone()))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/api/v1/contacts/" + bob.id())
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("aliasName", "Alice only"))))
                .andExpect(status().isOk());

        try {
            mockMvc.perform(patch("/api/v1/contacts/" + bob.id())
                            .header("Authorization", "Bearer " + carol.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("aliasName", "Carol"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

            getJson("/api/v1/contacts", alice.accessToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].contactUserId").value(bob.id().toString()))
                    .andExpect(jsonPath("$[0].aliasName").value("Alice only"));
            getJson("/api/v1/contacts", carol.accessToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].contactUserId").value(alice.id().toString()));
        } finally {
            mockMvc.perform(delete("/api/v1/contacts/" + bob.id())
                    .header("Authorization", "Bearer " + alice.accessToken()));
            mockMvc.perform(delete("/api/v1/contacts/" + alice.id())
                    .header("Authorization", "Bearer " + carol.accessToken()));
        }
    }

    @Test
    @Order(5)
    void nonOwnerCannotDeleteAnotherUsersContact() throws Exception {
        postJson("/api/v1/contacts", alice.accessToken(), Map.of("phone", bob.phone()))
                .andExpect(status().isCreated());
        postJson("/api/v1/contacts", carol.accessToken(), Map.of("phone", alice.phone()))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/api/v1/contacts/" + bob.id())
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("aliasName", "Alice only"))))
                .andExpect(status().isOk());

        try {
            mockMvc.perform(delete("/api/v1/contacts/" + bob.id())
                            .header("Authorization", "Bearer " + carol.accessToken()))
                    .andExpect(status().isNoContent());

            getJson("/api/v1/contacts", alice.accessToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].contactUserId").value(bob.id().toString()))
                    .andExpect(jsonPath("$[0].aliasName").value("Alice only"));
            getJson("/api/v1/contacts", carol.accessToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].contactUserId").value(alice.id().toString()));
        } finally {
            mockMvc.perform(delete("/api/v1/contacts/" + bob.id())
                    .header("Authorization", "Bearer " + alice.accessToken()));
            mockMvc.perform(delete("/api/v1/contacts/" + alice.id())
                    .header("Authorization", "Bearer " + carol.accessToken()));
        }
    }

    // ------------------------------------------------- direct conversations

    @Test
    @Order(6)
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
    @Order(7)
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
