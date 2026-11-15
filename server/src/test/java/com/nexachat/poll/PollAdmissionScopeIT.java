package com.nexachat.poll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.testutil.ItPhoneRanges;
import com.nexachat.testutil.PostgresTestDatabase;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Group polls obey the same member-admission boundary as message history:
 * a member admitted after a poll was created must not read, list, vote,
 * retract, close or delete it, and the change feed must not carry it —
 * every per-poll answer is {@code POLL_NOT_FOUND}, indistinguishable from
 * an unknown id. Admitted members keep full behavior, and rejoining restarts
 * the boundary at the new {@code joined_at}.
 *
 * <p>Same env-JDBC + {@link MockMvc} pattern as {@link PollFlowTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestExecutionListeners(
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS,
        listeners = {
                DependencyInjectionTestExecutionListener.class,
                DirtiesContextTestExecutionListener.class,
                EventPublishingTestExecutionListener.class
        })
class PollAdmissionScopeIT {

    private static final PostgresTestDatabase TEST_DATABASE =
            PostgresTestDatabase.create("poll-admission");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TEST_DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", TEST_DATABASE::username);
        registry.add("spring.datasource.password", TEST_DATABASE::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("nexachat.rate-limit.polls.create-per-minute", () -> "1000");
        registry.add("nexachat.rate-limit.polls.votes-per-minute", () -> "5000");
        registry.add("nexachat.rate-limit.polls.manage-per-minute", () -> "1000");
    }

    @AfterAll
    void dropDatabase() {
        try {
            javax.sql.DataSource dataSource = jdbcTemplate.getDataSource();
            if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
                hikari.close();
            }
        } finally {
            TEST_DATABASE.close();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;
    private TestUser carol;

    @BeforeAll
    void registerUsers() throws Exception {
        ItPhoneRanges.clean(jdbcTemplate, PollAdmissionScopeIT.class);
        String tag = "ITA" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
        carol = registerUser(tag + " Carol");
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = ItPhoneRanges.nextPhone(PollAdmissionScopeIT.class);
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

    private void addMember(TestUser caller, UUID group, TestUser member) throws Exception {
        postJson("/api/v1/conversations/" + group + "/members", caller.accessToken(),
                Map.of("userIds", List.of(member.id().toString())))
                .andExpect(status().isNoContent());
    }

    private void removeMember(TestUser caller, UUID group, TestUser member) throws Exception {
        deleteJson("/api/v1/conversations/" + group + "/members/" + member.id(),
                caller.accessToken())
                .andExpect(status().isNoContent());
    }

    private JsonNode createPoll(TestUser creator, UUID conversationId, String question,
                                List<String> options) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("options", options);
        MvcResult result = postJson(pollsPath(conversationId), creator.accessToken(), body)
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

    private void stampPollCreatedAt(UUID pollId, Instant createdAt) {
        jdbcTemplate.update("UPDATE polls SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), pollId);
    }

    private void stampJoinedAt(UUID group, UUID userId, Instant joinedAt) {
        jdbcTemplate.update(
                "UPDATE conversation_members SET joined_at = ?"
                        + " WHERE conversation_id = ? AND user_id = ?",
                Timestamp.from(joinedAt), group, userId);
    }

    private static List<UUID> optionIds(JsonNode poll) {
        List<UUID> ids = new ArrayList<>();
        poll.get("options").forEach(option -> ids.add(id(option.get("id"))));
        return ids;
    }

    /**
     * A group where {@code pre} was created before carol's admission and
     * {@code post} after it. Both timestamps and every {@code joined_at} are
     * stamped around one fixed instant so the boundary is exact for all
     * members: alice and bob are admitted to everything, carol only to
     * {@code post}.
     */
    private record Scoped(UUID group, JsonNode pre, JsonNode post) { }

    private Scoped admissionGroup() throws Exception {
        UUID group = createGroup(alice, List.of(bob), "Admission " + System.nanoTime());
        JsonNode pre = createPoll(alice, group, "Before join?", List.of("A", "B"));
        addMember(alice, group, carol);
        JsonNode post = createPoll(alice, group, "After join?", List.of("A", "B"));
        Instant boundary = Instant.now();
        stampJoinedAt(group, alice.id(), boundary.minusSeconds(60));
        stampJoinedAt(group, bob.id(), boundary.minusSeconds(60));
        stampJoinedAt(group, carol.id(), boundary);
        stampPollCreatedAt(id(pre.get("id")), boundary.minusSeconds(1));
        stampPollCreatedAt(id(post.get("id")), boundary.plusSeconds(1));
        return new Scoped(group, pre, post);
    }

    @Test
    void preAdmissionPollIsIndistinguishableFromAnUnknownId() throws Exception {
        Scoped s = admissionGroup();
        UUID preId = id(s.pre().get("id"));

        getJson(pollPath(s.group(), preId), carol.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
        // The same answer the API gives for a poll that does not exist.
        getJson(pollPath(s.group(), UUID.randomUUID()), carol.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
    }

    @Test
    void lateJoinerCannotVoteRetractCloseOrDeleteAPreAdmissionPoll() throws Exception {
        Scoped s = admissionGroup();
        UUID preId = id(s.pre().get("id"));
        String vote = objectMapper.writeValueAsString(
                Map.of("optionIds", List.of(optionIds(s.pre()).get(0).toString())));

        mockMvc.perform(put(pollPath(s.group(), preId) + "/vote")
                        .header("Authorization", "Bearer " + carol.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(vote))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
        deleteJson(pollPath(s.group(), preId) + "/vote", carol.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
        postJson(pollPath(s.group(), preId) + "/close", carol.accessToken(), Map.of())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
        deleteJson(pollPath(s.group(), preId), carol.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));

        // Even a group role cannot reach across the admission boundary.
        jdbcTemplate.update("UPDATE conversation_members SET role = 'ADMIN'"
                + " WHERE conversation_id = ? AND user_id = ?", s.group(), carol.id());
        postJson(pollPath(s.group(), preId) + "/close", carol.accessToken(), Map.of())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
        deleteJson(pollPath(s.group(), preId), carol.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));

        // No vote row was written by the rejected attempts.
        Integer votes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM poll_votes WHERE poll_id = ? AND user_id = ?",
                Integer.class, preId, carol.id());
        assertThat(votes).isZero();
    }

    @Test
    void listAndChangeFeedOmitPreAdmissionPolls() throws Exception {
        Scoped s = admissionGroup();
        UUID preId = id(s.pre().get("id"));
        UUID postId = id(s.post().get("id"));

        JsonNode list = objectMapper.readTree(getJson(pollsPath(s.group()), carol.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("id").asText()).isEqualTo(postId.toString());
        assertThat(list.has("nextBefore")).isFalse();

        JsonNode feed = objectMapper.readTree(getJson(
                pollsPath(s.group()) + "/changes?since=0", carol.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(feed.get("items")).hasSize(1);
        assertThat(feed.get("items").get(0).get("pollId").asText()).isEqualTo(postId.toString());

        // A cursor naming a poll the member cannot see answers exactly like
        // an unknown id: the same VALIDATION_FAILED as a random UUID.
        getJson(pollsPath(s.group()) + "?before=" + preId, carol.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        getJson(pollsPath(s.group()) + "?before=" + UUID.randomUUID(), carol.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void changeFeedHidesPreAdmissionTombstones() throws Exception {
        Scoped s = admissionGroup();
        UUID preId = id(s.pre().get("id"));
        deleteJson(pollPath(s.group(), preId), alice.accessToken())
                .andExpect(status().isNoContent());

        JsonNode feed = objectMapper.readTree(getJson(
                pollsPath(s.group()) + "/changes?since=0", carol.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(feed.get("items")).hasSize(1);
        assertThat(feed.get("items").get(0).get("pollId").asText())
                .isEqualTo(s.post().get("id").asText());

        JsonNode ownerFeed = objectMapper.readTree(getJson(
                pollsPath(s.group()) + "/changes?since=0", alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ownerFeed.get("items")).hasSize(2);
        assertThat(ownerFeed.get("items").get(1).get("deleted").asBoolean()).isTrue();
        assertThat(ownerFeed.get("items").get(1).get("pollId").asText()).isEqualTo(preId.toString());
    }

    @Test
    void lateJoinerKeepsFullBehaviorOnAnAdmittedPoll() throws Exception {
        Scoped s = admissionGroup();
        UUID postId = id(s.post().get("id"));
        List<UUID> options = optionIds(s.post());

        JsonNode voted = objectMapper.readTree(putJson(
                pollPath(s.group(), postId) + "/vote", carol.accessToken(),
                Map.of("optionIds", List.of(options.get(0).toString())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(voted.get("voterCount").asLong()).isEqualTo(1);
        assertThat(voted.get("options").get(0).get("voterIds").get(0).asText())
                .isEqualTo(carol.id().toString());

        JsonNode retracted = objectMapper.readTree(deleteJson(
                pollPath(s.group(), postId) + "/vote", carol.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(retracted.get("voterCount").asLong()).isZero();
    }

    @Test
    void originalMembersKeepFullBehavior() throws Exception {
        Scoped s = admissionGroup();
        UUID preId = id(s.pre().get("id"));
        List<UUID> options = optionIds(s.pre());

        for (TestUser viewer : List.of(alice, bob)) {
            getJson(pollPath(s.group(), preId), viewer.accessToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.question").value("Before join?"));
        }
        putJson(pollPath(s.group(), preId) + "/vote", bob.accessToken(),
                Map.of("optionIds", List.of(options.get(0).toString())))
                .andExpect(status().isOk());
        JsonNode list = objectMapper.readTree(getJson(pollsPath(s.group()), bob.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.get("items")).hasSize(2);
    }

    @Test
    void rejoinRestartsAdmissionAtTheNewJoinTime() throws Exception {
        Scoped s = admissionGroup();
        UUID preId = id(s.pre().get("id"));
        UUID postId = id(s.post().get("id"));

        // bob votes on the admitted poll, then leaves.
        putJson(pollPath(s.group(), postId) + "/vote", bob.accessToken(),
                Map.of("optionIds", List.of(optionIds(s.post()).get(0).toString())))
                .andExpect(status().isOk());
        removeMember(alice, s.group(), bob);

        JsonNode away = createPoll(alice, s.group(), "While away?", List.of("A", "B"));
        addMember(alice, s.group(), bob);
        // Every poll in the group predates bob's new admission, including the
        // one he voted on; a later poll is admitted again.
        JsonNode afterRejoin = createPoll(alice, s.group(), "Back again?", List.of("A", "B"));
        Instant rejoined = Instant.now();
        stampJoinedAt(s.group(), bob.id(), rejoined);
        stampPollCreatedAt(preId, rejoined.minusSeconds(1));
        stampPollCreatedAt(postId, rejoined.minusSeconds(1));
        stampPollCreatedAt(id(away.get("id")), rejoined.minusSeconds(1));
        stampPollCreatedAt(id(afterRejoin.get("id")), rejoined.plusSeconds(1));

        getJson(pollPath(s.group(), preId), bob.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
        getJson(pollPath(s.group(), postId), bob.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
        getJson(pollPath(s.group(), id(away.get("id"))), bob.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));
        putJson(pollPath(s.group(), postId) + "/vote", bob.accessToken(),
                Map.of("optionIds", List.of(optionIds(s.post()).get(1).toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLL_NOT_FOUND"));

        JsonNode list = objectMapper.readTree(getJson(pollsPath(s.group()), bob.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("id").asText())
                .isEqualTo(afterRejoin.get("id").asText());

        // The pre-rejoin vote row stays counted for members who can see the poll.
        JsonNode forOwner = objectMapper.readTree(getJson(
                pollPath(s.group(), postId), alice.accessToken())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(forOwner.get("voterCount").asLong()).isEqualTo(1);
    }
}
