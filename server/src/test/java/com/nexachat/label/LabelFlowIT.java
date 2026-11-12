package com.nexachat.label;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.testutil.TestWaits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL/API coverage for caller-owned conversation labels. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class LabelFlowIT {

    private static final AtomicInteger REGISTRATION_IP = new AtomicInteger();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env(
                "IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;

    private TestUser owner;
    private TestUser member;
    private TestUser outsider;
    private TestUser anotherMember;
    private UUID sharedConversationId;

    private record TestUser(UUID id, String accessToken) {
    }

    @BeforeEach
    void resetPrivateDatabaseAndRegisterUsers() throws Exception {
        jdbcTemplate.update("TRUNCATE users CASCADE");
        owner = registerUser("Label owner");
        member = registerUser("Conversation member");
        outsider = registerUser("Non-member");
        anotherMember = registerUser("Other conversation member");
        sharedConversationId = openDirectConversation(owner, member);
    }

    @Test
    void labelsCanBeCreatedListedPatchedAndDeletedIdempotently() throws Exception {
        MvcResult created = postJson("/api/v1/labels", owner.accessToken(),
                Map.of("name", "  Urgent  ", "colorId", "INDIGO"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode label = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID labelId = UUID.fromString(label.get("id").asText());
        assertThat(label.get("name").asText()).isEqualTo("Urgent");
        assertThat(label.get("colorId").asText()).isEqualTo("INDIGO");

        JsonNode listed = getJson("/api/v1/labels", owner.accessToken());
        assertThat(listed.size()).isEqualTo(1);
        assertThat(listed.get(0).get("id").asText()).isEqualTo(labelId.toString());

        mockMvc.perform(patch("/api/v1/labels/" + labelId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "Follow up", "colorId", "AMBER"))))
                .andExpect(status().isOk());
        JsonNode updated = getJson("/api/v1/labels", owner.accessToken()).get(0);
        assertThat(updated.get("name").asText()).isEqualTo("Follow up");
        assertThat(updated.get("colorId").asText()).isEqualTo("AMBER");

        UUID boundaryLabel = createLabel(owner, "🚗".repeat(30), "BLUE");
        deleteLabel(owner, boundaryLabel).andExpect(status().isNoContent());

        postJson("/api/v1/labels", owner.accessToken(),
                Map.of("name", " follow UP ", "colorId", "CYAN"))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/labels", owner.accessToken(),
                Map.of("name", "   ", "colorId", "BLUE"))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/labels", owner.accessToken(),
                Map.of("name", "x".repeat(31), "colorId", "BLUE"))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/labels", owner.accessToken(),
                Map.of("name", "Bad palette", "colorId", "NOT_A_COLOR"))
                .andExpect(status().isBadRequest());

        deleteLabel(owner, labelId).andExpect(status().isNoContent());
        deleteLabel(owner, labelId).andExpect(status().isNoContent());
        assertThat(getJson("/api/v1/labels", owner.accessToken()).size()).isZero();
    }

    @Test
    void labelsAndAssignmentsArePrivateAndWritesRequireConversationMembership() throws Exception {
        UUID ownerLabel = createLabel(owner, "Owner only", "ROSE");
        putAssignment(owner, sharedConversationId, ownerLabel).andExpect(status().isNoContent());

        assertThat(getJson("/api/v1/labels", member.accessToken()).size()).isZero();
        mockMvc.perform(patch("/api/v1/labels/" + ownerLabel)
                        .header(HttpHeaders.AUTHORIZATION, bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Stolen"))))
                .andExpect(status().isNotFound());
        putAssignment(member, sharedConversationId, ownerLabel)
                .andExpect(status().isNotFound());
        deleteLabel(member, ownerLabel).andExpect(status().isNoContent());
        assertThat(getJson("/api/v1/labels", owner.accessToken()).size()).isEqualTo(1);

        putAssignment(outsider, sharedConversationId, ownerLabel)
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/labels"))
                .andExpect(status().isUnauthorized());

        assertThat(stringValues(inbox(owner, null).get(0).get("labels")))
                .contains(ownerLabel.toString());
        assertThat(inbox(member, null).get(0).get("labels").size()).isZero();
    }

    @Test
    void assignmentCeilingIsIdempotentAndCanBeReusedAfterRemoval() throws Exception {
        List<UUID> labels = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            labels.add(createLabel(owner, "Label " + index, "BLUE"));
        }

        for (int index = 0; index < 3; index++) {
            putAssignment(owner, sharedConversationId, labels.get(index))
                    .andExpect(status().isNoContent());
        }
        putAssignment(owner, sharedConversationId, labels.get(0))
                .andExpect(status().isNoContent());
        putAssignment(owner, sharedConversationId, labels.get(3))
                .andExpect(status().isBadRequest());

        deleteAssignment(owner, sharedConversationId, labels.get(0))
                .andExpect(status().isNoContent());
        deleteAssignment(owner, sharedConversationId, labels.get(0))
                .andExpect(status().isNoContent());
        putAssignment(owner, sharedConversationId, labels.get(3))
                .andExpect(status().isNoContent());
        assertThat(inbox(owner, null).get(0).get("labels").size()).isEqualTo(3);
    }

    @Test
    void concurrentAssignmentsNeverExceedThreePerConversation() throws Exception {
        List<UUID> labels = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            labels.add(createLabel(owner, "Concurrent assignment " + index, "TEAL"));
        }

        List<Integer> statuses = concurrentAssignments(owner, sharedConversationId, labels);
        assertThat(statuses.stream().filter(code -> code == 204).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(code -> code == 400).count()).isEqualTo(2);
        assertThat(inbox(owner, null).get(0).get("labels").size()).isEqualTo(3);
    }

    @Test
    void assignmentQueuedBehindLabelDeletionReturnsNotFoundInsteadOfIntegrityError() throws Exception {
        UUID labelId = createLabel(owner, "Racing delete", "ROSE");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection lockHolder = dataSource.getConnection()) {
            lockHolder.setAutoCommit(false);
            try (PreparedStatement lockLabel = lockHolder.prepareStatement(
                    "SELECT id FROM conversation_labels WHERE id = ? FOR UPDATE")) {
                lockLabel.setObject(1, labelId);
                assertThat(lockLabel.executeQuery().next()).isTrue();
            }

            CountDownLatch deleteStarted = new CountDownLatch(1);
            Future<Integer> deletion = executor.submit(() -> {
                deleteStarted.countDown();
                return deleteLabel(owner, labelId).andReturn().getResponse().getStatus();
            });
            assertThat(deleteStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitLabelLockWaiters(1);

            CountDownLatch assignmentStarted = new CountDownLatch(1);
            Future<Integer> assignment = executor.submit(() -> {
                assignmentStarted.countDown();
                return putAssignment(owner, sharedConversationId, labelId)
                        .andReturn().getResponse().getStatus();
            });
            assertThat(assignmentStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitLabelLockWaiters(2);

            lockHolder.commit();
            assertThat(deletion.get(20, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(assignment.get(20, TimeUnit.SECONDS)).isEqualTo(404);
            assertThat(assignmentCount(sharedConversationId, owner.id())).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentLabelCreatesNeverExceedTenPerUser() throws Exception {
        for (int index = 0; index < 8; index++) {
            createLabel(owner, "Existing " + index, "INDIGO");
        }

        List<Integer> statuses = concurrentCreates(owner, List.of(
                "Concurrent A", "Concurrent B", "Concurrent C", "Concurrent D", "Concurrent E"));
        assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(2);
        assertThat(statuses.stream().filter(code -> code == 400).count()).isEqualTo(3);
        assertThat(getJson("/api/v1/labels", owner.accessToken()).size()).isEqualTo(10);
    }

    @Test
    void concurrentCaseInsensitiveDuplicateCreatesHaveOneWinner() throws Exception {
        List<Integer> statuses = concurrentCreates(owner, List.of("Priority", " priority "));
        assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(code -> code == 400).count()).isEqualTo(1);
        assertThat(getJson("/api/v1/labels", owner.accessToken()).size()).isEqualTo(1);
    }

    @Test
    void inboxFilterCarriesCallerLabelsAcrossKeysetPages() throws Exception {
        UUID urgent = createLabel(owner, "Urgent", "AMBER");
        UUID another = openDirectConversation(owner, outsider);
        UUID unlabeled = openDirectConversation(owner, anotherMember);
        putAssignment(owner, sharedConversationId, urgent).andExpect(status().isNoContent());
        putAssignment(owner, another, urgent).andExpect(status().isNoContent());
        UUID privateLabel = createLabel(member, "Member label", "TEAL");
        putAssignment(member, sharedConversationId, privateLabel)
                .andExpect(status().isNoContent());

        MvcResult first = mockMvc.perform(get("/api/v1/conversations")
                        .param("labelId", urgent.toString())
                        .param("limit", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstPage = objectMapper.readTree(first.getResponse().getContentAsString());
        String cursor = first.getResponse().getHeader("X-Next-Cursor");
        assertThat(cursor).isNotBlank();
        assertThat(firstPage.size()).isEqualTo(1);
        assertThat(firstPage.get(0).get("labels").size()).isEqualTo(1);
        assertThat(firstPage.get(0).get("labels").get(0).asText()).isEqualTo(urgent.toString());

        MvcResult second = mockMvc.perform(get("/api/v1/conversations")
                        .param("labelId", urgent.toString())
                        .param("limit", "1")
                        .param("cursor", cursor)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondPage = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondPage.size()).isEqualTo(1);
        assertThat(second.getResponse().getHeader("X-Next-Cursor")).isNull();
        assertThat(List.of(firstPage.get(0).get("id").asText(), secondPage.get(0).get("id").asText()))
                .containsExactlyInAnyOrder(sharedConversationId.toString(), another.toString())
                .doesNotContain(unlabeled.toString());

        assertThat(inbox(member, "?labelId=" + privateLabel).size()).isEqualTo(1);
        assertThat(inbox(member, "?labelId=" + urgent).size()).isZero();
        assertThat(inbox(owner, "?labelId=" + privateLabel).size()).isZero();
    }

    @Test
    void deletingLabelsAndRemovingOrLeavingMembershipClearAssignments() throws Exception {
        UUID labelToDelete = createLabel(owner, "Temporary", "PURPLE");
        putAssignment(owner, sharedConversationId, labelToDelete)
                .andExpect(status().isNoContent());
        deleteLabel(owner, labelToDelete).andExpect(status().isNoContent());
        assertThat(inbox(owner, "?labelId=" + labelToDelete).size()).isZero();
        assertThat(inbox(owner, null).get(0).get("labels").size()).isZero();

        UUID removedMemberConversation = createGroup(owner, member);
        UUID memberLabel = createLabel(member, "Personal", "CYAN");
        putAssignment(member, removedMemberConversation, memberLabel)
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/conversations/" + removedMemberConversation
                        + "/members/" + member.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(assignmentCount(removedMemberConversation, member.id())).isZero();
        postJson("/api/v1/conversations/" + removedMemberConversation + "/members",
                owner.accessToken(), Map.of("userIds", List.of(member.id().toString())))
                .andExpect(status().isNoContent());
        assertThat(inbox(member, "?labelId=" + memberLabel))
                .noneMatch(entry -> entry.get("id").asText().equals(removedMemberConversation.toString()));

        UUID leavingMemberConversation = createGroup(owner, member);
        putAssignment(member, leavingMemberConversation, memberLabel)
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/conversations/" + leavingMemberConversation + "/leave")
                        .header(HttpHeaders.AUTHORIZATION, bearer(member)))
                .andExpect(status().isNoContent());
        assertThat(assignmentCount(leavingMemberConversation, member.id())).isZero();
    }

    @Test
    void labelWritesShareAThirtyPerMinuteUserRateLimit() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            statuses.add(deleteLabel(member, UUID.randomUUID()).andReturn().getResponse().getStatus());
        }
        assertThat(statuses.subList(0, 30)).containsOnly(204);
        assertThat(statuses.get(30)).isEqualTo(429);
    }

    private TestUser registerUser(String displayName) throws Exception {
        int address = 1 + Math.floorMod(REGISTRATION_IP.incrementAndGet(), 200);
        RequestPostProcessor remoteAddress = request -> {
            request.setRemoteAddr("192.0.2." + address);
            return request;
        };
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", randomPhone(),
                                "password", "secret123",
                                "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    private UUID openDirectConversation(TestUser opener, TestUser partner) throws Exception {
        MvcResult result = postJson("/api/v1/conversations/direct", opener.accessToken(),
                Map.of("userId", partner.id().toString()))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID createGroup(TestUser admin, TestUser invited) throws Exception {
        MvcResult result = postJson("/api/v1/conversations/group", admin.accessToken(),
                Map.of("title", "Label test " + UUID.randomUUID(),
                        "memberIds", List.of(invited.id().toString())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID createLabel(TestUser user, String name, String colorId) throws Exception {
        MvcResult result = postJson("/api/v1/labels", user.accessToken(),
                Map.of("name", name, "colorId", colorId))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private List<Integer> concurrentCreates(TestUser user, List<String> names) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(names.size());
        CountDownLatch ready = new CountDownLatch(names.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < names.size(); index++) {
                String name = names.get(index);
                Callable<Integer> request = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent request barrier timed out");
                    }
                    return postJson("/api/v1/labels", user.accessToken(),
                            Map.of("name", name, "colorId", "INDIGO"))
                            .andReturn().getResponse().getStatus();
                };
                futures.add(executor.submit(request));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(20, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    private List<Integer> concurrentAssignments(TestUser user, UUID conversationId,
                                                List<UUID> labelIds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(labelIds.size());
        CountDownLatch ready = new CountDownLatch(labelIds.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (UUID labelId : labelIds) {
                Callable<Integer> request = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent request barrier timed out");
                    }
                    return putAssignment(user, conversationId, labelId)
                            .andReturn().getResponse().getStatus();
                };
                futures.add(executor.submit(request));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(20, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    private JsonNode getJson(String path, String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode inbox(TestUser user, String query) throws Exception {
        return getJson("/api/v1/conversations" + (query == null ? "" : query),
                user.accessToken());
    }

    private static List<String> stringValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private void awaitLabelLockWaiters(int minimum) {
        TestWaits.until(minimum + " label transaction(s) to block on the label row lock", () -> {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND query ILIKE '%conversation_label%'
                    """, Integer.class);
            return waiting != null && waiting >= minimum;
        });
    }

    private org.springframework.test.web.servlet.ResultActions postJson(
            String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions putAssignment(
            TestUser user, UUID conversationId, UUID labelId) throws Exception {
        return mockMvc.perform(put("/api/v1/conversations/" + conversationId
                        + "/labels/" + labelId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private org.springframework.test.web.servlet.ResultActions deleteAssignment(
            TestUser user, UUID conversationId, UUID labelId) throws Exception {
        return mockMvc.perform(delete("/api/v1/conversations/" + conversationId
                        + "/labels/" + labelId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private org.springframework.test.web.servlet.ResultActions deleteLabel(
            TestUser user, UUID labelId) throws Exception {
        return mockMvc.perform(delete("/api/v1/labels/" + labelId)
                .header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private long assignmentCount(UUID conversationId, UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_label_assignments"
                        + " WHERE conversation_id = ? AND user_id = ?",
                Long.class, conversationId, userId);
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }

    private static String randomPhone() {
        return ItPhoneRanges.nextPhone(LabelFlowIT.class);
    }
}
