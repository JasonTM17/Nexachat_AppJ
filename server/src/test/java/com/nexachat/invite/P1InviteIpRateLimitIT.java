package com.nexachat.invite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.auth.RateLimitService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
    private TestUser bob;
    private TestUser carol;

    @BeforeEach
    void resetRateLimitBuckets() {
        rateLimitService.reset();
    }

    @BeforeAll
    void setUp() {
        alice = createTestUser("RateLimit Alice");
        dave = createTestUser("RateLimit Dave");
        bob = createTestUser("RateLimit Bob");
        carol = createTestUser("RateLimit Carol");
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

        for (int i = 1; i <= 60; i++) {
            TestUser caller = i % 2 == 0 ? bob : carol;
            ResponseEntity<String> response = resolve(token, caller, "10.30.1." + i);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> overLimit = resolve(token, bob, "10.30.2.1");

        assertThat(overLimit.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
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
