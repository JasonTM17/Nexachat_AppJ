package com.nexachat.contact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.testutil.ItPhoneRanges;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContactByEmailIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> env("IT_DB_URL",
                "jdbc:postgresql://localhost:5433/nexachat_cmp_cemail"));
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

    private TestUser alice;
    private TestUser bob;
    private String bobVerifiedEmail;

    private record TestUser(UUID id, String phone, String displayName, String accessToken) {
    }

    @BeforeAll
    void registerUsers() throws Exception {
        String like = "phone LIKE '" + ItPhoneRanges.prefixFor(ContactByEmailIT.class) + "%'";
        jdbcTemplate.update(
                "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update("DELETE FROM contacts WHERE owner_id IN (SELECT id FROM users WHERE " + like + ")");
        jdbcTemplate.update("DELETE FROM users WHERE " + like);

        alice = registerUser("EmailAlice");
        bob = registerUser("EmailBob");
        bobVerifiedEmail = "bob-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update(
                "UPDATE users SET email = ?, email_verified_at = ? WHERE id = ?",
                bobVerifiedEmail, Timestamp.from(Instant.now()), bob.id());
    }

    private static String randomPhone() {
        return ItPhoneRanges.nextPhone(ContactByEmailIT.class);
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

    @Test
    @Order(1)
    void searchEmail_exactMatch_isCaseInsensitive() throws Exception {
        mockMvc.perform(get("/api/v1/users/search")
                        .param("email", "  " + bobVerifiedEmail.toUpperCase() + "  ")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bob.id().toString()));

        mockMvc.perform(get("/api/v1/users/search")
                        .param("email", bobVerifiedEmail)
                        .param("phone", bob.phone())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @Order(2)
    void searchEmail_unknownUnverifiedAndDeleted_matchPhoneEmptySemantics() throws Exception {
        mockMvc.perform(get("/api/v1/users/search")
                        .param("email", "nobody-" + UUID.randomUUID() + "@example.com")
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        jdbcTemplate.update(
                "UPDATE users SET email = ?, email_verified_at = NULL WHERE id = ?",
                "pending-" + UUID.randomUUID() + "@example.com", bob.id());
        mockMvc.perform(get("/api/v1/users/search")
                        .param("email", bobVerifiedEmail)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        jdbcTemplate.update(
                "UPDATE users SET email = ?, email_verified_at = ? WHERE id = ?",
                bobVerifiedEmail, Timestamp.from(Instant.now()), bob.id());
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), bob.id());
        mockMvc.perform(get("/api/v1/users/search")
                        .param("email", bobVerifiedEmail)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        jdbcTemplate.update("UPDATE users SET deleted_at = NULL WHERE id = ?", bob.id());

        mockMvc.perform(get("/api/v1/users/search")
                        .param("email", bobVerifiedEmail)
                        .header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @Order(3)
    void addContact_byEmail_lifecycle() throws Exception {
        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "  " + bobVerifiedEmail.toUpperCase() + "  "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contactUserId").value(bob.id().toString()));

        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", bobVerifiedEmail))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_CONTACT"));

        String aliceEmail = "alice-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update(
                "UPDATE users SET email = ?, email_verified_at = ? WHERE id = ?",
                aliceEmail, Timestamp.from(Instant.now()), alice.id());
        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", aliceEmail))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @Order(4)
    void unknownEmail_addContact_matchesUnknownPhoneEnvelope() throws Exception {
        String unknownEmail = "ghost-" + UUID.randomUUID() + "@example.com";
        MvcResult byEmail = mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", unknownEmail))))
                .andExpect(status().isNotFound())
                .andReturn();

        MvcResult byPhone = mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", ItPhoneRanges.prefixFor(ContactByEmailIT.class) + "999998"))))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode emailError = objectMapper.readTree(byEmail.getResponse().getContentAsString());
        JsonNode phoneError = objectMapper.readTree(byPhone.getResponse().getContentAsString());
        assertThat(emailError).isEqualTo(phoneError);
    }

    @Test
    @Order(5)
    void addContact_xorValidation_and_rateLimit() throws Exception {
        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", bob.phone(), "email", bobVerifiedEmail))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        TestUser rateLimitUser = registerUser("RateLimitAlice");
        for (int i = 0; i < 30; i++) {
            String unknownPhone = ItPhoneRanges.prefixFor(ContactByEmailIT.class)
                    + String.format("%06d", 990000 + i);
            mockMvc.perform(post("/api/v1/contacts")
                            .header("Authorization", "Bearer " + rateLimitUser.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("phone", unknownPhone))))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(post("/api/v1/contacts")
                        .header("Authorization", "Bearer " + rateLimitUser.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phone", ItPhoneRanges.prefixFor(ContactByEmailIT.class) + "999999"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }
}
