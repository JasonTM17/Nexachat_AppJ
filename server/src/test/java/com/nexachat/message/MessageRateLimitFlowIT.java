package com.nexachat.message;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageRateLimitFlowIT {

    private static final int REQUEST_COUNT = 31;

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
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private record TestUser(UUID id, String accessToken) { }

    private TestUser sender;

    @BeforeAll
    void registerSender() throws Exception {
        String phone = ItPhoneRanges.nextPhone(MessageRateLimitFlowIT.class);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", phone,
                                "password", "secret123",
                                "displayName", "Rate limit sender"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        sender = new TestUser(UUID.fromString(body.get("user").get("id").asText()),
                body.get("accessToken").asText());
    }

    @Test
    void restMessageCollectionAcceptsThirtyRequestsAndRejectsTheNextConcurrentRequest() throws Exception {
        String path = "/api/v1/conversations/" + UUID.randomUUID() + "/messages";
        // Validation returns 400 after interception; avoiding database writes keeps the burst narrow.
        ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> responses = new ArrayList<>();
        try {
            for (int request = 0; request < REQUEST_COUNT; request++) {
                responses.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Rate-limit request gate timed out");
                    }
                    return mockMvc.perform(post(path)
                                    .header("Authorization", "Bearer " + sender.accessToken())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                            .andReturn();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MvcResult> completed = new ArrayList<>();
            for (Future<MvcResult> response : responses) {
                completed.add(response.get(20, TimeUnit.SECONDS));
            }

            assertThat(completed).filteredOn(result -> result.getResponse().getStatus() == 400).hasSize(30);
            List<MvcResult> limited = completed.stream()
                    .filter(result -> result.getResponse().getStatus() == 429)
                    .toList();
            assertThat(limited).hasSize(1);
            JsonNode error = objectMapper.readTree(limited.get(0).getResponse().getContentAsString())
                    .get("error");
            assertThat(error.get("code").asText()).isEqualTo("RATE_LIMITED");
            assertThat(error.get("details").get("retryAfterSeconds").asLong()).isPositive();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
