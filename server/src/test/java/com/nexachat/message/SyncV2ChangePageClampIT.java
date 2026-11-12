package com.nexachat.message;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.message.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V2 change pages are capped at fifty rows even when the client asks for up to one hundred;
 * client asks for the legacy 1–100 ceiling; legacy negotiation keeps the 100-row cap.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncV2ChangePageClampIT {

    private static final int MUTATION_COUNT = 60;
    private static final int REQUESTED_LIMIT = 100;
    private static final int V2_CHANGE_PAGE_MAX = 50;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MessageService messageService;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser bob;

    @BeforeAll
    void registerUsers() throws Exception {
        String tag = "SYNCV2CLAMP" + System.nanoTime();
        alice = registerUser(tag + " Alice");
        bob = registerUser(tag + " Bob");
    }

    @Test
    void v2ChangePageClampsToFiftyAndContinuesWithoutGapOrDuplicate() throws Exception {
        UUID conversationId = createGroup("v2-clamp-" + System.nanoTime());
        List<UUID> messageIds = seedMessages(conversationId, MUTATION_COUNT);

        long clientCursor = 0L;
        Long clientGeneration = null;
        for (int page = 0; page < 5; page++) {
            JsonNode baseline = syncV2(bob, conversationId, clientCursor, clientGeneration, REQUESTED_LIMIT);
            JsonNode baseDelta = singleDelta(baseline);
            clientCursor = baseDelta.path("changeCursor").asLong();
            clientGeneration = baseDelta.path("generation").asLong();
            if (!baseDelta.path("changeHasMore").asBoolean(false)) {
                break;
            }
        }

        for (int i = 0; i < MUTATION_COUNT; i++) {
            messageService.edit(alice.id(), conversationId, messageIds.get(i), "edited-" + i);
        }

        JsonNode page1 = syncV2(bob, conversationId, clientCursor, clientGeneration, REQUESTED_LIMIT);
        JsonNode delta1 = singleDelta(page1);
        assertThat(delta1.path("changeHasMore").asBoolean()).isTrue();
        assertThat(delta1.withArray("changes").size()).isEqualTo(V2_CHANGE_PAGE_MAX);

        long cursorAfterPage1 = delta1.path("changeCursor").asLong();
        long generationAfterPage1 = delta1.path("generation").asLong();
        assertThat(cursorAfterPage1).isGreaterThan(clientCursor);
        List<Long> revisionsPage1 = changeVersions(delta1);
        assertThat(revisionsPage1).isSorted().doesNotHaveDuplicates();

        JsonNode page2 = syncV2(bob, conversationId, cursorAfterPage1, generationAfterPage1, REQUESTED_LIMIT);
        JsonNode delta2 = singleDelta(page2);
        assertThat(delta2.path("changeHasMore").asBoolean()).isFalse();
        int remaining = MUTATION_COUNT - V2_CHANGE_PAGE_MAX;
        assertThat(delta2.withArray("changes").size()).isEqualTo(remaining);

        long cursorAfterPage2 = delta2.path("changeCursor").asLong();
        assertThat(cursorAfterPage2).isGreaterThan(cursorAfterPage1);
        List<Long> revisionsPage2 = changeVersions(delta2);
        assertThat(revisionsPage2).isSorted().doesNotHaveDuplicates();
        assertThat(revisionsPage2.get(0)).isGreaterThan(revisionsPage1.get(revisionsPage1.size() - 1));
    }

    @Test
    void legacySyncStillHonorsLimitUpToOneHundred() throws Exception {
        UUID conversationId = createGroup("v1-clamp-" + System.nanoTime());
        int userMessages = 60;
        seedMessages(conversationId, userMessages);

        JsonNode response = syncV1(bob, conversationId, 0L, REQUESTED_LIMIT);
        JsonNode delta = singleDelta(response);
        assertThat(delta.path("hasMore").asBoolean()).isFalse();
        assertThat(delta.withArray("newMessages").size())
                .isGreaterThanOrEqualTo(userMessages);
    }

    private List<UUID> seedMessages(UUID conversationId, int count) throws Exception {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = messageService.send(alice.id(), conversationId,
                            new SendMessageRequest(UUID.randomUUID(), "TEXT", "original-" + i, null, List.of()))
                    .message().id();
            ids.add(id);
        }
        return ids;
    }

    private static List<Long> changeVersions(JsonNode delta) {
        List<Long> versions = new ArrayList<>();
        for (JsonNode row : delta.withArray("changes")) {
            versions.add(row.path("changeVersion").asLong());
        }
        return versions;
    }

    private JsonNode syncV2(TestUser user, UUID conversationId, long changeCursor,
                            Long generation, int limit) throws Exception {
        Map<String, Object> watermark = new LinkedHashMap<>();
        watermark.put("conversationId", conversationId.toString());
        watermark.put("lastSeq", 0L);
        watermark.put("changeCursor", changeCursor);
        if (generation != null) {
            watermark.put("generation", generation);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("syncVersion", 2);
        body.put("watermarks", List.of(watermark));
        body.put("limitPerConv", limit);
        return postSync(user, body);
    }

    private JsonNode syncV1(TestUser user, UUID conversationId, long lastSeq, int limit) throws Exception {
        Map<String, Object> watermark = Map.of(
                "conversationId", conversationId.toString(),
                "lastSeq", lastSeq);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("watermarks", List.of(watermark));
        body.put("limitPerConv", limit);
        return postSync(user, body);
    }

    private JsonNode postSync(TestUser user, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/sync")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode singleDelta(JsonNode response) {
        JsonNode deltas = response.get("deltas");
        assertThat(deltas).hasSize(1);
        return deltas.get(0);
    }

    private TestUser registerUser(String displayName) throws Exception {
        String phone = ItPhoneRanges.nextPhone(SyncV2ChangePageClampIT.class);
        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone, "password", "secret123", "displayName", displayName))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    private UUID createGroup(String title) throws Exception {
        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", title, "memberIds", List.of(bob.id().toString())))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }
}
