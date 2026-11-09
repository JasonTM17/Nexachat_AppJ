package com.nexachat.poll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.poll.dto.VoteRequest;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.event.EventPublishingTestExecutionListener;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of group polls against a real PostgreSQL: authz matrix,
 * single vs multiple choice, anonymous results, expiry, close/delete,
 * removed-member votes, concurrent votes by one user, list paging and the
 * change feed used by clients when no WebSocket event exists.
 *
 * <p>Named {@code *Test} (not {@code *IT}) so the module's {@code mvn test}
 * gate runs it: the poll feature is server-only in this slice and these
 * behaviours cannot be proven by a mock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// This suite has no @MockBean, so skip Spring's Mockito reset listener and
// keep only the listeners that drive injection and context lifecycle.
@TestExecutionListeners(
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS,
        listeners = {
                DependencyInjectionTestExecutionListener.class,
                DirtiesContextTestExecutionListener.class,
                EventPublishingTestExecutionListener.class
        })
class PollFlowTest {

    private static final String SCHEMA = "nexachat_poll_it";

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        String baseUrl = env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
        String user = env("IT_DB_USER", "nexachat");
        String password = env("IT_DB_PASSWORD", "nexachat");
        ensureSchema(baseUrl, user, password);
        registry.add("spring.datasource.url", () -> withSchema(baseUrl));
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        // The suite exercises many mutations from the same fixture users; keep
        // the production buckets but give the test run headroom.
        registry.add("nexachat.rate-limit.polls.create-per-minute", () -> "1000");
        registry.add("nexachat.rate-limit.polls.votes-per-minute", () -> "5000");
        registry.add("nexachat.rate-limit.polls.manage-per-minute", () -> "1000");
    }

    private static String withSchema(String baseUrl) {
        if (baseUrl.contains("currentSchema=")) {
            return baseUrl;
        }
        // public stays on the search path so database-level extensions installed
        // by a sibling suite (pg_trgm, unaccent) are visible to V1/V4.
        return baseUrl + (baseUrl.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA + ",public";
    }

    private static void ensureSchema(String baseUrl, String user, String password) {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                baseUrl, user, password);
             java.sql.Statement statement = connection.createStatement()) {
            // The migrations reference public.unaccent explicitly and use the
            // public pg_trgm operator class; install them at database level
            // before the isolated schema is created.
            statement.executeUpdate("CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public");
            statement.executeUpdate("CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public");
            statement.executeUpdate("CREATE EXTENSION IF NOT EXISTS unaccent SCHEMA public");
            statement.executeUpdate("CREATE EXTENSION IF NOT EXISTS btree_gin SCHEMA public");
            statement.executeUpdate("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.executeUpdate("CREATE SCHEMA " + SCHEMA);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Could not prepare the poll test schema", ex);
        }
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
    private PollService pollService;

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol;
    private TestUser dave;

    @BeforeAll
    void registerUsers() throws Exception {
        cleanupFixtures();
        String tag = "ITP" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
        dave = registerUser(tag + " Dave");
    }

    @AfterAll
    void removeFixtures() {
        cleanupFixtures();
    }

    private void cleanupFixtures() {
        String like = "phone LIKE '+8493%'";
        jdbcTemplate.update("DELETE FROM poll_votes WHERE poll_id IN ("
                + "SELECT id FROM polls WHERE created_by IN (SELECT id FROM users WHERE " + like + ")"
                + " OR conversation_id IN (SELECT id FROM conversations WHERE created_by IN"
                + " (SELECT id FROM users WHERE " + like + ")))");
        jdbcTemplate.update("DELETE FROM polls WHERE created_by IN (SELECT id FROM users WHERE " + like + ")"
                + " OR conversation_id IN (SELECT id FROM conversations WHERE created_by IN"
                + " (SELECT id FROM users WHERE " + like + "))");
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update(
                "DELETE FROM message_attachments WHERE owner_id IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update("DELETE FROM users WHERE " + like);
    }

    private static String randomPhone() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "+8493" + String.format("%07d", random.nextInt(10_000_000));
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
                phone, displayName, body.get("accessToken").asText());
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

    private ResultActions putJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(put(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteJson(String path, String accessToken) throws Exception {
        return mockMvc.perform(delete(path).header("Authorization", "Bearer " + accessToken));
    }

    private UUID createGroup(TestUser creator, List<TestUser> members, String title) throws Exception {
        MvcResult result = postJson("/api/v1/conversations/group", creator.accessToken(),
                Map.of("title", title,
                        "memberIds", members.stream().map(u -> u.id().toString()).toList()))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID openDirect(TestUser opener, TestUser partner) throws Exception {
        MvcResult result = postJson("/api/v1/conversations/direct", opener.accessToken(),
                Map.of("userId", partner.id().toString()))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsString()).get("id").asText());
    }

    private Map<String, Object> pollBody(String question, List<String> options,
                                         boolean multipleChoice, boolean anonymous, Instant closesAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("options", options);
        body.put("multipleChoice", multipleChoice);
        body.put("anonymous", anonymous);
        if (closesAt != null) {
            body.put("closesAt", closesAt.toString());
        }
        return body;
    }

    private JsonNode createPoll(TestUser creator, UUID conversationId, String question,
                                List<String> options, boolean multipleChoice, boolean anonymous,
                                Instant closesAt) throws Exception {
        MvcResult result = postJson(pollsPath(conversationId), creator.accessToken(),
                pollBody(question, options, multipleChoice, anonymous, closesAt))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String pollsPath(UUID conversationId) {
        return "/api/v1/conversations/" + conversationId + "/polls";
    }

    private static String pollPath(UUID conversationId, UUID pollId) {
        return pollsPath(conversationId) + "/" + pollId;
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.asText());
    }

    private static List<UUID> optionIds(JsonNode poll) {
        List<UUID> ids = new ArrayList<>();
        poll.get("options").forEach(option -> ids.add(id(option.get("id"))));
        return ids;
    }

    private static long optionCount(JsonNode poll, int position) {
        return poll.get("options").get(position).get("voteCount").asLong();
    }

    private static List<String> voterIds(JsonNode poll, int position) {
        List<String> ids = new ArrayList<>();
        JsonNode voters = poll.get("options").get(position).get("voterIds");
        if (voters != null) {
            voters.forEach(voter -> ids.add(voter.asText()));
        }
        return ids;
    }

    private static List<String> myOptionIds(JsonNode poll) {
        List<String> ids = new ArrayList<>();
        poll.get("myOptionIds").forEach(option -> ids.add(option.asText()));
        return ids;
    }

    // --------------------------------------------------------------- authz

    @Test
    void nonMembersAreRejectedEverywhere() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Authz group " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Lunch?", List.of("A", "B"), false, false, null);
        UUID pollId = id(poll.get("id"));
        String vote = objectMapper.writeValueAsString(
                Map.of("optionIds", List.of(optionIds(poll).get(0).toString())));

        getJson(pollsPath(group), carol.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        getJson(pollPath(group, pollId), carol.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        postJson(pollsPath(group), carol.accessToken(),
                pollBody("Nope?", List.of("A", "B"), false, false, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        mockMvc.perform(put(pollPath(group, pollId) + "/vote")
                        .header("Authorization", "Bearer " + carol.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(vote))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        postJson(pollPath(group, pollId) + "/close", carol.accessToken(), Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        deleteJson(pollPath(group, pollId), carol.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));

        mockMvc.perform(get(pollsPath(group)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void directConversationsAreRejectedWithNotAGroup() throws Exception {
        UUID direct = openDirect(alice, carol);

        postJson(pollsPath(direct), alice.accessToken(),
                pollBody("Lunch?", List.of("A", "B"), false, false, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NOT_A_GROUP"));
        getJson(pollsPath(direct), alice.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NOT_A_GROUP"));
        getJson(pollPath(direct, UUID.randomUUID()), alice.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NOT_A_GROUP"));
        putJson(pollPath(direct, UUID.randomUUID()) + "/vote", alice.accessToken(),
                Map.of("optionIds", List.of(UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NOT_A_GROUP"));
        postJson(pollPath(direct, UUID.randomUUID()) + "/close", alice.accessToken(), Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NOT_A_GROUP"));
        deleteJson(pollPath(direct, UUID.randomUUID()), alice.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NOT_A_GROUP"));

        // A non-member still learns nothing about the direct conversation.
        getJson(pollsPath(direct), dave.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
    }

    @Test
    void unknownConversationsAndForeignPollsAreNotFound() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Lookup group " + System.nanoTime());
        UUID other = createGroup(alice, List.of(bob), "Other group " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Pick", List.of("A", "B"), false, false, null);

        getJson(pollsPath(UUID.randomUUID()), alice.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"));
        getJson(pollPath(other, id(poll.get("id"))), alice.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
        getJson(pollPath(group, UUID.randomUUID()), alice.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
    }

    // ---------------------------------------------------------- creation

    @Test
    void createValidatesQuestionOptionsAndExpiry() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Validation group " + System.nanoTime());
        String path = pollsPath(group);

        postJson(path, alice.accessToken(), pollBody(" ", List.of("A", "B"), false, false, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        postJson(path, alice.accessToken(),
                pollBody("q".repeat(201), List.of("A", "B"), false, false, null))
                .andExpect(status().isBadRequest());
        postJson(path, alice.accessToken(), pollBody("Only one?", List.of("A"), false, false, null))
                .andExpect(status().isBadRequest());
        postJson(path, alice.accessToken(),
                pollBody("Too many?", List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"),
                        false, false, null))
                .andExpect(status().isBadRequest());
        postJson(path, alice.accessToken(),
                pollBody("Long option", List.of("A", "x".repeat(101)), false, false, null))
                .andExpect(status().isBadRequest());
        postJson(path, alice.accessToken(),
                pollBody("Duplicate", List.of("Same", "Same"), false, false, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        postJson(path, alice.accessToken(),
                pollBody("Past", List.of("A", "B"), false, false, Instant.now().minusSeconds(60)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        postJson(path, alice.accessToken(),
                pollBody("Too far", List.of("A", "B"), false, false,
                        Instant.now().plus(Duration.ofDays(31))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        JsonNode created = createPoll(alice, group, "  Trim me  ",
                List.of("  Option A  ", "Option B"), false, false,
                Instant.now().plus(Duration.ofDays(29)));
        assertThat(created.get("question").asText()).isEqualTo("Trim me");
        assertThat(created.get("options").get(0).get("label").asText()).isEqualTo("Option A");
        assertThat(created.get("multipleChoice").asBoolean()).isFalse();
        assertThat(created.get("anonymous").asBoolean()).isFalse();
        assertThat(created.get("status").asText()).isEqualTo("OPEN");
        assertThat(created.get("version").asLong()).isPositive();
        assertThat(optionIds(created)).hasSize(2);
    }

    @Test
    void listIsNewestFirstAndCursorPaged() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Paging group " + System.nanoTime());
        JsonNode first = createPoll(alice, group, "P1", List.of("A", "B"), false, false, null);
        Thread.sleep(5);
        JsonNode second = createPoll(alice, group, "P2", List.of("A", "B"), false, false, null);
        Thread.sleep(5);
        JsonNode third = createPoll(alice, group, "P3", List.of("A", "B"), false, false, null);

        MvcResult pageOne = getJson(pollsPath(group) + "?limit=2", alice.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode one = objectMapper.readTree(pageOne.getResponse().getContentAsString());
        assertThat(one.get("items")).hasSize(2);
        assertThat(one.get("items").get(0).get("question").asText()).isEqualTo("P3");
        assertThat(one.get("items").get(1).get("question").asText()).isEqualTo("P2");
        String nextBefore = one.get("nextBefore").asText();

        MvcResult pageTwo = getJson(pollsPath(group) + "?limit=2&before=" + nextBefore,
                alice.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        JsonNode two = objectMapper.readTree(pageTwo.getResponse().getContentAsString());
        assertThat(two.get("items")).hasSize(1);
        assertThat(two.get("items").get(0).get("question").asText()).isEqualTo("P1");
        assertThat(two.has("nextBefore")).isFalse();

        assertThat(id(first.get("id"))).isNotEqualTo(id(second.get("id")));
        assertThat(id(third.get("id"))).isNotEqualTo(id(second.get("id")));
    }

    // ------------------------------------------------------------- voting

    @Test
    void singleChoiceVoteReplacesAndRetractIsIdempotent() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Single group " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "One?", List.of("A", "B", "C"), false, false, null);
        UUID pollId = id(poll.get("id"));
        List<UUID> options = optionIds(poll);
        long version = poll.get("version").asLong();

        JsonNode voted = vote(bob, group, pollId, options.get(0));
        assertThat(optionCount(voted, 0)).isEqualTo(1);
        assertThat(myOptionIds(voted)).containsExactly(options.get(0).toString());
        assertThat(voterIds(voted, 0)).containsExactly(bob.id().toString());
        assertThat(voted.get("voterCount").asLong()).isEqualTo(1);
        assertThat(voted.get("version").asLong()).isGreaterThan(version);

        long afterFirstVote = voted.get("version").asLong();
        JsonNode changed = vote(bob, group, pollId, options.get(1));
        assertThat(optionCount(changed, 0)).isZero();
        assertThat(optionCount(changed, 1)).isEqualTo(1);
        assertThat(myOptionIds(changed)).containsExactly(options.get(1).toString());
        assertThat(changed.get("version").asLong()).isGreaterThan(afterFirstVote);

        long afterChange = changed.get("version").asLong();
        JsonNode retracted = retractVote(bob, group, pollId);
        assertThat(retracted.get("voterCount").asLong()).isZero();
        assertThat(myOptionIds(retracted)).isEmpty();
        assertThat(retracted.get("version").asLong()).isGreaterThan(afterChange);

        long afterRetract = retracted.get("version").asLong();
        JsonNode retractedAgain = retractVote(bob, group, pollId);
        assertThat(retractedAgain.get("voterCount").asLong()).isZero();
        assertThat(retractedAgain.get("version").asLong()).isEqualTo(afterRetract);
    }

    private JsonNode vote(TestUser user, UUID conversationId, UUID pollId, UUID optionId)
            throws Exception {
        MvcResult result = putJson(pollPath(conversationId, pollId) + "/vote",
                user.accessToken(), Map.of("optionIds", List.of(optionId.toString())))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void multipleChoiceReplacesTheWholeSetAtomically() throws Exception {
        UUID group = createGroup(alice, List.of(bob, carol), "Multi group " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Many?", List.of("A", "B", "C"), true, false, null);
        UUID pollId = id(poll.get("id"));
        List<UUID> options = optionIds(poll);

        JsonNode first = putVote(bob, group, pollId, options.get(0), options.get(1));
        assertThat(optionCount(first, 0)).isEqualTo(1);
        assertThat(optionCount(first, 1)).isEqualTo(1);
        assertThat(optionCount(first, 2)).isZero();
        assertThat(first.get("voterCount").asLong()).isEqualTo(1);

        long version = first.get("version").asLong();
        JsonNode replaced = putVote(bob, group, pollId, options.get(1), options.get(2));
        assertThat(optionCount(replaced, 0)).isZero();
        assertThat(optionCount(replaced, 1)).isEqualTo(1);
        assertThat(optionCount(replaced, 2)).isEqualTo(1);
        assertThat(myOptionIds(replaced)).containsExactly(
                options.get(1).toString(), options.get(2).toString());
        assertThat(replaced.get("version").asLong()).isGreaterThan(version);

        JsonNode carolVote = putVote(carol, group, pollId, options.get(0), options.get(0));
        assertThat(carolVote.get("voterCount").asLong()).isEqualTo(2);
        assertThat(optionCount(carolVote, 0)).isEqualTo(1);

        JsonNode retracted = retractVote(bob, group, pollId);
        assertThat(retracted.get("voterCount").asLong()).isEqualTo(1);
        assertThat(myOptionIds(retracted)).isEmpty();
        assertThat(optionCount(retracted, 0)).isEqualTo(1);
        assertThat(optionCount(retracted, 1)).isZero();
    }

    private JsonNode retractVote(TestUser user, UUID conversationId, UUID pollId)
            throws Exception {
        MvcResult result = deleteJson(pollPath(conversationId, pollId) + "/vote",
                user.accessToken())
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode putVote(TestUser user, UUID conversationId, UUID pollId, UUID... options)
            throws Exception {
        MvcResult result = putJson(pollPath(conversationId, pollId) + "/vote", user.accessToken(),
                Map.of("optionIds", List.of(options).stream().map(UUID::toString).toList()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void voteValidatesOptionOwnershipAndChoiceArity() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Vote validation " + System.nanoTime());
        UUID other = createGroup(alice, List.of(bob), "Vote validation other " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Pick", List.of("A", "B"), false, false, null);
        UUID pollId = id(poll.get("id"));
        List<UUID> options = optionIds(poll);
        JsonNode foreign = createPoll(alice, other, "Foreign", List.of("A", "B"), false, false, null);
        UUID foreignOption = optionIds(foreign).get(0);

        putJson(pollPath(group, pollId) + "/vote", bob.accessToken(),
                Map.of("optionIds", List.of(options.get(0).toString(), options.get(1).toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        putJson(pollPath(group, pollId) + "/vote", bob.accessToken(),
                Map.of("optionIds", List.of(foreignOption.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        putJson(pollPath(group, pollId) + "/vote", bob.accessToken(),
                Map.of("optionIds", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anonymousResultsNeverExposeVoterIds() throws Exception {
        UUID group = createGroup(alice, List.of(bob, carol), "Anon group " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Secret?", List.of("A", "B"), true, true, null);
        UUID pollId = id(poll.get("id"));
        List<UUID> options = optionIds(poll);

        putVote(bob, group, pollId, options.get(0));
        putVote(carol, group, pollId, options.get(0), options.get(1));

        for (TestUser viewer : List.of(alice, bob, carol)) {
            String raw = getJson(pollPath(group, pollId), viewer.accessToken())
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode body = objectMapper.readTree(raw);
            assertThat(body.get("anonymous").asBoolean()).isTrue();
            assertThat(body.get("voterCount").asLong()).isEqualTo(2);
            body.get("options").forEach(option -> assertThat(option.has("voterIds")).isFalse());
            assertThat(raw).doesNotContain(bob.id().toString());
            assertThat(raw).doesNotContain(carol.id().toString());
        }

        String listRaw = getJson(pollsPath(group), alice.accessToken())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listRaw).doesNotContain(bob.id().toString());
        assertThat(listRaw).doesNotContain(carol.id().toString());
        JsonNode listed = objectMapper.readTree(listRaw).get("items").get(0);
        listed.get("options").forEach(option -> assertThat(option.has("voterIds")).isFalse());

        // The change feed carries the same poll body and must not leak either.
        String feedRaw = getJson(pollsPath(group) + "/changes?since=0", alice.accessToken())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(feedRaw).doesNotContain(bob.id().toString());
        assertThat(feedRaw).doesNotContain(carol.id().toString());

        // The creator sees counts but no voter list either.
        assertThat(optionCount(listed, 0)).isEqualTo(2);
        assertThat(optionCount(listed, 1)).isEqualTo(1);
    }

    @Test
    void nonAnonymousResultsExposeSortedVoterIds() throws Exception {
        UUID group = createGroup(alice, List.of(bob, carol), "Voters group " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Voters?", List.of("A", "B"), false, false, null);
        UUID pollId = id(poll.get("id"));
        UUID option = optionIds(poll).get(0);

        vote(bob, group, pollId, option);
        vote(carol, group, pollId, option);
        JsonNode result = objectMapper.readTree(getJson(pollPath(group, pollId), alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        List<String> voters = voterIds(result, 0);
        assertThat(voters).containsExactlyInAnyOrder(bob.id().toString(), carol.id().toString());
        assertThat(voters).isSorted();
        assertThat(voterIds(result, 1)).isEmpty();
    }

    // -------------------------------------------------------- close/delete

    @Test
    void closeRequiresCreatorOrAdminAndBlocksVoting() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Close group " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Close me?", List.of("A", "B"), false, false, null);
        UUID pollId = id(poll.get("id"));
        List<UUID> options = optionIds(poll);
        vote(bob, group, pollId, options.get(0));

        postJson(pollPath(group, pollId) + "/close", bob.accessToken(), Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));

        JsonNode closed = objectMapper.readTree(postJson(pollPath(group, pollId) + "/close",
                alice.accessToken(), Map.of())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.has("closedAt")).isTrue();
        assertThat(closed.get("closedBy").asText()).isEqualTo(alice.id().toString());
        long closedVersion = closed.get("version").asLong();

        JsonNode closedAgain = objectMapper.readTree(postJson(pollPath(group, pollId) + "/close",
                alice.accessToken(), Map.of())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(closedAgain.get("version").asLong()).isEqualTo(closedVersion);

        putJson(pollPath(group, pollId) + "/vote", bob.accessToken(),
                Map.of("optionIds", List.of(options.get(1).toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POLL_CLOSED"));
        deleteJson(pollPath(group, pollId) + "/vote", bob.accessToken())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POLL_CLOSED"));

        // Replaying the exact existing selection is a safe no-op, not a conflict.
        putJson(pollPath(group, pollId) + "/vote", bob.accessToken(),
                Map.of("optionIds", List.of(options.get(0).toString())))
                .andExpect(status().isOk());

        // A group admin who is not the creator may close a poll.
        UUID adminGroup = createGroup(alice, List.of(bob), "Admin close " + System.nanoTime());
        JsonNode adminPoll = createPoll(alice, adminGroup, "Admin close?",
                List.of("A", "B"), false, false, null);
        UUID adminPollId = id(adminPoll.get("id"));
        jdbcTemplate.update("UPDATE conversation_members SET role = 'ADMIN'"
                + " WHERE conversation_id = ? AND user_id = ?", adminGroup, bob.id());
        postJson(pollPath(adminGroup, adminPollId) + "/close", bob.accessToken(), Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void expiryBlocksVotingButKeepsResultsReadable() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Expiry group " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Expires?", List.of("A", "B"), false, false,
                Instant.now().plus(Duration.ofHours(1)));
        UUID pollId = id(poll.get("id"));
        List<UUID> options = optionIds(poll);
        vote(bob, group, pollId, options.get(0));

        jdbcTemplate.update("UPDATE polls SET closes_at = now() - interval '1 second' WHERE id = ?",
                pollId);

        JsonNode expired = objectMapper.readTree(getJson(pollPath(group, pollId), alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(expired.get("status").asText()).isEqualTo("CLOSED");
        assertThat(expired.has("closedAt")).isFalse();
        assertThat(optionCount(expired, 0)).isEqualTo(1);

        putJson(pollPath(group, pollId) + "/vote", bob.accessToken(),
                Map.of("optionIds", List.of(options.get(1).toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POLL_CLOSED"));
        deleteJson(pollPath(group, pollId) + "/vote", bob.accessToken())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POLL_CLOSED"));
    }

    @Test
    void deleteIsManageOnlyIdempotentAndHidesThePoll() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Delete group " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Delete me?", List.of("A", "B"), false, false, null);
        UUID pollId = id(poll.get("id"));

        deleteJson(pollPath(group, pollId), bob.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
        deleteJson(pollPath(group, pollId), alice.accessToken())
                .andExpect(status().isNoContent());
        deleteJson(pollPath(group, pollId), alice.accessToken())
                .andExpect(status().isNoContent());
        // A member who never had manage rights cannot turn the idempotent
        // replay into a success that implies they deleted the poll.
        deleteJson(pollPath(group, pollId), bob.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
        getJson(pollPath(group, pollId), alice.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
        getJson(pollsPath(group), alice.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        // A group admin who is not the creator may delete.
        UUID adminGroup = createGroup(alice, List.of(bob), "Admin delete " + System.nanoTime());
        JsonNode adminPoll = createPoll(alice, adminGroup, "Admin delete?",
                List.of("A", "B"), false, false, null);
        jdbcTemplate.update("UPDATE conversation_members SET role = 'ADMIN'"
                + " WHERE conversation_id = ? AND user_id = ?", adminGroup, bob.id());
        deleteJson(pollPath(adminGroup, id(adminPoll.get("id"))), bob.accessToken())
                .andExpect(status().isNoContent());
    }

    @Test
    void closeAndDeleteByNonCreatorAdminWorkAfterPromotion() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Promoted group " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Promoted?", List.of("A", "B"), false, false, null);
        jdbcTemplate.update("UPDATE conversation_members SET role = 'ADMIN'"
                + " WHERE conversation_id = ? AND user_id = ?", group, bob.id());
        postJson(pollPath(group, id(poll.get("id"))) + "/close", bob.accessToken(), Map.of())
                .andExpect(status().isOk());
    }

    // ----------------------------------------------------- removed members

    @Test
    void removedMemberVotesStayCountedButTheyCannotVote() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Removed group " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Who?", List.of("A", "B"), false, false, null);
        UUID pollId = id(poll.get("id"));
        List<UUID> options = optionIds(poll);
        vote(bob, group, pollId, options.get(0));

        deleteJson("/api/v1/conversations/" + group + "/members/" + bob.id(), alice.accessToken())
                .andExpect(status().isNoContent());

        getJson(pollPath(group, pollId), bob.accessToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));
        putJson(pollPath(group, pollId) + "/vote", bob.accessToken(),
                Map.of("optionIds", List.of(options.get(1).toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_MEMBER"));

        JsonNode result = objectMapper.readTree(getJson(pollPath(group, pollId), alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(optionCount(result, 0)).isEqualTo(1);
        assertThat(result.get("voterCount").asLong()).isEqualTo(1);
        assertThat(voterIds(result, 0)).containsExactly(bob.id().toString());
    }

    // ------------------------------------------------------- concurrency

    @Test
    void concurrentSingleChoiceVotesByOneUserNeverDoubleCount() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Concurrent single " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Race?", List.of("A", "B"), false, false, null);
        UUID pollId = id(poll.get("id"));
        UUID optionA = optionIds(poll).get(0);
        UUID optionB = optionIds(poll).get(1);

        runConcurrently(
                () -> pollService.vote(bob.id(), group, pollId, new VoteRequest(List.of(optionA))),
                () -> pollService.vote(bob.id(), group, pollId, new VoteRequest(List.of(optionB))));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM poll_votes WHERE poll_id = ? AND user_id = ?",
                Integer.class, pollId, bob.id());
        assertThat(rows).isEqualTo(1);

        JsonNode state = objectMapper.readTree(getJson(pollPath(group, pollId), alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(state.get("voterCount").asLong()).isEqualTo(1);
        assertThat(optionCount(state, 0) + optionCount(state, 1)).isEqualTo(1);

        // Replaying the same option concurrently is still a single vote row.
        runConcurrently(
                () -> pollService.vote(bob.id(), group, pollId, new VoteRequest(List.of(optionB))),
                () -> pollService.vote(bob.id(), group, pollId, new VoteRequest(List.of(optionB))));
        Integer sameRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM poll_votes WHERE poll_id = ? AND user_id = ?",
                Integer.class, pollId, bob.id());
        assertThat(sameRows).isEqualTo(1);
    }

    @Test
    void concurrentMultipleChoiceSetsReplaceAtomically() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Concurrent multi " + System.nanoTime());
        JsonNode poll = createPoll(alice, group, "Race set?", List.of("A", "B", "C"), true, false, null);
        UUID pollId = id(poll.get("id"));
        List<UUID> options = optionIds(poll);

        runConcurrently(
                () -> pollService.vote(bob.id(), group, pollId,
                        new VoteRequest(List.of(options.get(0), options.get(1)))),
                () -> pollService.vote(bob.id(), group, pollId,
                        new VoteRequest(List.of(options.get(1), options.get(2)))));

        List<UUID> rows = jdbcTemplate.queryForList(
                "SELECT option_id FROM poll_votes WHERE poll_id = ? AND user_id = ?",
                UUID.class, pollId, bob.id());
        assertThat(rows).hasSize(2);
        Set<String> actual = new HashSet<>();
        rows.forEach(option -> actual.add(option.toString()));
        assertThat(actual).isIn(
                Set.of(options.get(0).toString(), options.get(1).toString()),
                Set.of(options.get(1).toString(), options.get(2).toString()));
    }

    private void runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> one = executor.submit(() -> {
                await(start);
                first.run();
                return null;
            });
            Future<?> two = executor.submit(() -> {
                await(start);
                second.run();
                return null;
            });
            start.countDown();
            one.get(20, TimeUnit.SECONDS);
            two.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ----------------------------------------------------------- change feed

    @Test
    void changeFeedReportsCurrentStateAndDeleteTombstones() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Delta group " + System.nanoTime());
        JsonNode first = createPoll(alice, group, "Delta one?", List.of("A", "B"),
                false, false, null);
        UUID firstId = id(first.get("id"));
        List<UUID> options = optionIds(first);
        long firstVersion = first.get("version").asLong();
        JsonNode second = createPoll(alice, group, "Delta two?", List.of("A", "B"),
                false, false, null);
        long secondVersion = second.get("version").asLong();
        assertThat(secondVersion).isGreaterThan(firstVersion);

        JsonNode createdFeed = objectMapper.readTree(getJson(
                pollsPath(group) + "/changes?since=0", alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(createdFeed.get("items")).hasSize(2);
        assertThat(createdFeed.get("items").get(0).get("version").asLong()).isEqualTo(firstVersion);
        assertThat(createdFeed.get("items").get(1).get("version").asLong()).isEqualTo(secondVersion);
        assertThat(createdFeed.get("items").get(0).get("deleted").asBoolean()).isFalse();
        assertThat(createdFeed.get("items").get(0).get("poll").get("question").asText())
                .isEqualTo("Delta one?");
        assertThat(createdFeed.get("nextSince").asLong()).isEqualTo(secondVersion);

        JsonNode emptyFeed = objectMapper.readTree(getJson(
                pollsPath(group) + "/changes?since=" + secondVersion, alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(emptyFeed.get("items")).isEmpty();
        assertThat(emptyFeed.get("nextSince").asLong()).isEqualTo(secondVersion);

        vote(bob, group, firstId, options.get(0));
        long votedVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM polls WHERE id = ?", Long.class, firstId);
        assertThat(votedVersion).isGreaterThan(secondVersion);
        JsonNode votedFeed = objectMapper.readTree(getJson(
                pollsPath(group) + "/changes?since=" + secondVersion, alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(votedFeed.get("items")).hasSize(1);
        assertThat(votedFeed.get("items").get(0).get("poll").get("voterCount").asLong()).isEqualTo(1);

        // The feed is a compacted current-state feed: two changes to the same
        // poll between reads collapse into one item at its latest version.
        JsonNode compacted = objectMapper.readTree(getJson(
                pollsPath(group) + "/changes?since=" + firstVersion, alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(compacted.get("items")).hasSize(2);
        assertThat(compacted.get("items").get(0).get("pollId").asText())
                .isEqualTo(second.get("id").asText());
        assertThat(compacted.get("items").get(1).get("pollId").asText())
                .isEqualTo(firstId.toString());

        JsonNode page = objectMapper.readTree(getJson(
                pollsPath(group) + "/changes?since=" + firstVersion + "&limit=1",
                alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("version").asLong()).isEqualTo(secondVersion);
        assertThat(page.get("hasMore").asBoolean()).isTrue();
        assertThat(page.get("nextSince").asLong()).isEqualTo(secondVersion);

        JsonNode nextPage = objectMapper.readTree(getJson(
                pollsPath(group) + "/changes?since=" + page.get("nextSince").asLong(),
                alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(nextPage.get("items")).hasSize(1);
        assertThat(nextPage.get("items").get(0).get("version").asLong()).isEqualTo(votedVersion);
        assertThat(nextPage.get("hasMore").asBoolean()).isFalse();

        deleteJson(pollPath(group, firstId), alice.accessToken())
                .andExpect(status().isNoContent());
        Long deletedVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM polls WHERE id = ?", Long.class, firstId);
        JsonNode tombstone = objectMapper.readTree(getJson(
                pollsPath(group) + "/changes?since=" + votedVersion, alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(tombstone.get("items")).hasSize(1);
        assertThat(tombstone.get("items").get(0).get("deleted").asBoolean()).isTrue();
        assertThat(tombstone.get("items").get(0).get("pollId").asText())
                .isEqualTo(firstId.toString());
        assertThat(tombstone.get("items").get(0).has("poll")).isFalse();
        assertThat(tombstone.get("nextSince").asLong()).isEqualTo(deletedVersion);
    }

    @Test
    void changeFeedRejectsInvalidCursorsAndLimits() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Delta validation " + System.nanoTime());
        getJson(pollsPath(group) + "/changes?since=-1", alice.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        getJson(pollsPath(group) + "/changes?limit=0", alice.accessToken())
                .andExpect(status().isBadRequest());
        getJson(pollsPath(group) + "?limit=0", alice.accessToken())
                .andExpect(status().isBadRequest());
        getJson(pollsPath(group) + "?limit=101", alice.accessToken())
                .andExpect(status().isBadRequest());
        getJson(pollsPath(group) + "?before=" + UUID.randomUUID(), alice.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}
