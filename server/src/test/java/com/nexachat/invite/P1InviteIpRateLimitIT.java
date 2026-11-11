package com.nexachat.invite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.auth.RateLimitService;
import com.nexachat.testutil.MutableTimeMeter;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
@TestPropertySource(properties = "server.forward-headers-strategy=framework")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P1InviteIpRateLimitIT {

    /** Frozen bucket time: 60 requests must exhaust the minute budget exactly. */
    private static final MutableTimeMeter BUCKET_TIME = new MutableTimeMeter();

    @TestConfiguration(proxyBeanMethods = false)
    static class FrozenBucketTime {
        @Bean
        @Primary
        TimeMeter frozenRateLimitTimeMeter() {
            return BUCKET_TIME;
        }
    }

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
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private RateLimitService rateLimitService;
    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser dave;
    private final List<TestUser> resolvers = new ArrayList<>();
    private TestUser fresh;

    @BeforeEach
    void resetRateLimitBuckets() {
        rateLimitService.reset();
    }

    @BeforeAll
    void setUp() {
        alice = createTestUser("RateLimit Alice");
        dave = createTestUser("RateLimit Dave");
        // Six preview callers keep every per-user bucket at 10 of 30, so the
        // request that must be denied can only be stopped by the shared IP bucket.
        for (int index = 0; index < 6; index++) {
            resolvers.add(createTestUser("RateLimit Resolver " + index));
        }
        fresh = createTestUser("RateLimit Fresh");
    }

    @Test
    void spoofedForwardedForCannotResetThePerIpBucket() throws Exception {
        UUID groupId = createGroup(List.of(dave.id()));
        MvcResult link = mockMvc.perform(post("/api/v1/conversations/{id}/invite-link", groupId)
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requiresApproval\":false}"))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(link.getResponse().getContentAsString())
                .get("token").asText();

        for (int i = 0; i < 60; i++) {
            TestUser caller = resolvers.get(i % resolvers.size());
            ResponseEntity<String> response = resolve(token, caller, "10.30.1." + (i + 1));
            assertThat(response.getStatusCode())
                    .as("preview %d stays inside the shared 60/minute budget", i + 1)
                    .isEqualTo(HttpStatus.OK);
        }

        // The fresh user has not consumed its own 30/minute budget. A 429 here can
        // therefore only come from the socket-peer bucket shared by all callers; if
        // X-Forwarded-For were honoured, this new address would get a fresh bucket
        // and the request would be 200 (the failure observed in full verify runs).
        ResponseEntity<String> denied = resolve(token, fresh, "10.30.9.99");
        assertThat(denied.getStatusCode())
                .as("spoofed X-Forwarded-For must not reset the peer bucket")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Proof that the bucket clock is the injected meter: two seconds of frozen
        // time refill exactly two of the sixty tokens. A real system clock would
        // have refilled an unpredictable number during the 60 previews above.
        BUCKET_TIME.advance(Duration.ofSeconds(2));
        for (int index = 0; index < 2; index++) {
            assertThat(resolve(token, fresh, "10.30.9." + (100 + index)).getStatusCode())
                    .as("refilled token %d", index + 1)
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(resolve(token, fresh, "10.30.9.200").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseEntity<String> resolve(String token, TestUser caller, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(caller.accessToken());
        headers.set("X-Forwarded-For", forwardedFor);
        return restTemplate.exchange("/api/v1/invite-links/" + token, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private TestUser createTestUser(String displayName) {
        String phone = "+8493" + String.format("%07d",
                ThreadLocalRandom.current().nextInt(10_000_000));
        User user = User.create(phone, "hash", displayName);
        user = userRepository.saveAndFlush(user);
        return new TestUser(user.getId(), jwtService.generateAccessToken(user));
    }

    private UUID createGroup(List<UUID> memberIds) throws Exception {
        List<String> ids = memberIds.stream().map(UUID::toString).toList();
        MvcResult result = mockMvc.perform(post("/api/v1/conversations/group")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Invite IP limit", "memberIds", ids))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }
}
