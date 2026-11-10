package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.conversation.InboxService;
import com.nexachat.invite.GroupJoinRequest;
import com.nexachat.invite.InviteService;
import com.nexachat.label.LabelService;
import com.nexachat.message.MessageService;
import com.nexachat.message.ReactionService;
import com.nexachat.message.dto.ReactRequest;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.testing.SqlStatementCounter;
import com.nexachat.testing.SqlStatementCounter.Measurement;
import com.nexachat.testing.SqlStatementCounterTestConfiguration;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SQL statement ceilings on a fixed inbox, message, sync, interaction, label and invite dataset. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Import(SqlStatementCounterTestConfiguration.class)
class AuthorizationSqlBudgetIT {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationSqlBudgetIT.class);
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger(
            Math.floorMod(System.nanoTime(), 10_000_000));
    private static final int JOIN_REQUESTS = 8;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private SqlStatementCounter sqlCounter;

    @Test
    void readAndWriteHotPathsRetainExplicitTransactionBoundaries() throws Exception {
        assertTransaction(MessageService.class, "getHistory", true,
                UUID.class, UUID.class, Long.class, Long.class, Integer.class);
        assertTransaction(MessageService.class, "send", false,
                UUID.class, UUID.class, SendMessageRequest.class);
        assertTransaction(MessageService.class, "syncDelta", false,
                UUID.class, com.nexachat.message.dto.SyncRequest.class);
        assertTransaction(ReactionService.class, "toggle", false,
                UUID.class, UUID.class, ReactRequest.class);
        assertTransaction(ReactionService.class, "add", false,
                UUID.class, UUID.class, String.class);
        assertTransaction(InboxService.class, "listInbox", true, UUID.class, Boolean.class);
        assertTransaction(LabelService.class, "list", true, UUID.class);
        assertTransaction(LabelService.class, "create", false,
                UUID.class, com.nexachat.label.CreateLabelRequest.class);
        assertTransaction(LabelService.class, "assign", false, UUID.class, UUID.class, UUID.class);
        assertTransaction(InviteService.class, "listJoinRequests", true,
                UUID.class, UUID.class, GroupJoinRequest.Status.class, Integer.class);
    }

    @Test
    void hotPathBudgetsStayBoundedWithFixedDatasetAndEightJoinRequests() throws Exception {
        TestUser owner = createUser("Budget owner");
        TestUser member = createUser("Budget member");
        List<TestUser> requesters = new ArrayList<>();
        for (int i = 0; i < JOIN_REQUESTS; i++) {
            requesters.add(createUser("Budget requester " + i));
        }

        UUID groupId = createGroup(owner, member);
        UUID directOne = createDirect(owner, requesters.get(0));
        UUID directTwo = createDirect(owner, requesters.get(1));
        List<UUID> conversationIds = List.of(groupId, directOne, directTwo);

        List<UUID> messageIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            messageIds.add(sendMentionedMessage(groupId, owner, member, i));
        }
        mockMvc.perform(put("/api/v1/messages/{messageId}/reactions/{emoji}",
                        messageIds.get(0), "👍")
                        .header("Authorization", bearer(member.token())))
                .andExpect(status().isNoContent());

        UUID labelId = createLabel(owner);
        for (UUID conversationId : conversationIds) {
            mockMvc.perform(put("/api/v1/conversations/{conversationId}/labels/{labelId}",
                            conversationId, labelId)
                            .header("Authorization", bearer(owner.token())))
                    .andExpect(status().isNoContent());
        }

        String inviteToken = createApprovalInvite(owner, groupId);
        for (int i = 0; i < requesters.size(); i++) {
            mockMvc.perform(post("/api/v1/invite-links/{token}/join", inviteToken)
                            .header("Authorization", bearer(requesters.get(i).token()))
                            .with(remoteAddr("203.0.113." + (10 + i))))
                    .andExpect(status().isCreated());
        }

        // Warm the JWT validity cache before each measured request so the budget
        // covers the endpoint query plan rather than one-time authentication IO.
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());

        measure("GET /api/v1/conversations (inbox, 3 conversations)", 16,
                () -> mockMvc.perform(get("/api/v1/conversations")
                                .header("Authorization", bearer(owner.token())))
                        .andExpect(status().isOk()).andReturn());

        measure("GET /api/v1/conversations/{id}/messages (8 messages, mentions, reactions)", 16,
                () -> mockMvc.perform(get("/api/v1/conversations/{id}/messages", groupId)
                                .param("limit", "20")
                                .header("Authorization", bearer(owner.token())))
                        .andExpect(status().isOk()).andReturn());

        List<Map<String, Object>> watermarks = conversationIds.stream()
                .map(id -> Map.<String, Object>of("conversationId", id, "lastSeq", 0L))
                .toList();
        measure("POST /api/v1/sync (3 conversations)", 36,
                () -> postJson("/api/v1/sync", owner, Map.of("watermarks", watermarks))
                        .andExpect(status().isOk()).andReturn());

        measure("PUT /api/v1/messages/{id}/reactions/{emoji}", 16,
                () -> mockMvc.perform(put("/api/v1/messages/{messageId}/reactions/{emoji}",
                                        messageIds.get(1), "❤️")
                                .header("Authorization", bearer(member.token())))
                        .andExpect(status().isNoContent()).andReturn());

        measure("GET /api/v1/labels (JdbcTemplate-backed labels)", 3,
                () -> mockMvc.perform(get("/api/v1/labels")
                                .header("Authorization", bearer(owner.token())))
                        .andExpect(status().isOk()).andReturn());

        measure("GET /api/v1/conversations/{id}/join-requests (8 pending rows)", 6,
                () -> mockMvc.perform(get("/api/v1/conversations/{id}/join-requests", groupId)
                                .header("Authorization", bearer(owner.token())))
                        .andExpect(status().isOk()).andReturn());
    }

    private TestUser createUser(String displayName) {
        int suffix = PHONE_SEQUENCE.incrementAndGet();
        String phone = "+849055" + String.format("%07d", Math.floorMod(suffix, 10_000_000));
        User user = userRepository.saveAndFlush(User.create(phone, "x".repeat(60), displayName));
        return new TestUser(user.getId(), jwtService.generateAccessToken(user));
    }

    private UUID createGroup(TestUser owner, TestUser member) throws Exception {
        MvcResult result = postJson("/api/v1/conversations/group", owner,
                        Map.of("title", "SQL budget group", "memberIds", List.of(member.userId().toString())))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private UUID createDirect(TestUser owner, TestUser peer) throws Exception {
        MvcResult result = postJson("/api/v1/conversations/direct", owner,
                        Map.of("userId", peer.userId().toString()))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private UUID sendMentionedMessage(UUID conversationId, TestUser owner, TestUser member, int index)
            throws Exception {
        Map<String, Object> mention = Map.of("userId", member.userId().toString(), "offset", 3, "length", 4);
        MvcResult result = postJson("/api/v1/conversations/" + conversationId + "/messages", owner,
                        Map.of("clientMessageId", UUID.randomUUID().toString(), "type", "TEXT",
                                "content", "Hi @Bob " + index, "mentions", List.of(mention), "mentionAll", false))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("id").asText());
    }

    private UUID createLabel(TestUser owner) throws Exception {
        MvcResult result = postJson("/api/v1/labels", owner,
                        Map.of("name", "Budget label", "colorId", "INDIGO"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.path("id").asText());
    }

    private String createApprovalInvite(TestUser owner, UUID conversationId) throws Exception {
        MvcResult result = postJson("/api/v1/conversations/" + conversationId + "/invite-link", owner,
                        Map.of("requiresApproval", true))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, TestUser user, Object body)
            throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", bearer(user.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void measure(String label, int budget, Callable<MvcResult> action) throws Exception {
        Measurement<MvcResult> measurement = sqlCounter.measure(action);
        int count = measurement.sqlExecutions();
        log.info("SQL_BUDGET path={} statements={} budget={}", label, count, budget);
        assertThat(count).as("SQL statement budget for " + label).isLessThanOrEqualTo(budget);
    }

    private static void assertTransaction(Class<?> serviceType, String methodName,
                                          boolean readOnly, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Transactional transactional = serviceType.getMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class);
        assertThat(transactional).as(serviceType.getSimpleName() + "." + methodName + " transaction")
                .isNotNull();
        assertThat(transactional.readOnly()).as(serviceType.getSimpleName() + "." + methodName + " readOnly")
                .isEqualTo(readOnly);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private record TestUser(UUID userId, String token) {
    }
}
