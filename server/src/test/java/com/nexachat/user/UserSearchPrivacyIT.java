package com.nexachat.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Privacy rules for GET /api/v1/users/search — display-name hits omit phone unless
 * the match is already in the caller's contacts; exact phone lookup stays exact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserSearchPrivacyIT {

  private static final String PHONE_PREFIX = "+8495";

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

  private String nameTag;
  private TestUser searcher;
  private TestUser target;

  @BeforeAll
  void seedUsers() throws Exception {
    String like = "phone LIKE '" + PHONE_PREFIX + "%'";
    jdbcTemplate.update(
            "DELETE FROM conversations WHERE created_by IN (SELECT id FROM users WHERE " + like + ")");
    jdbcTemplate.update(
            "DELETE FROM contacts WHERE owner_id IN (SELECT id FROM users WHERE " + like + ")");
    jdbcTemplate.update("DELETE FROM users WHERE " + like);

    nameTag = "Sch" + System.nanoTime();
    searcher = register(nameTag + " A");
    target = register(nameTag + " B");
  }

  private record TestUser(UUID id, String phone, String accessToken) {
  }

  private static String randomPhone() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return PHONE_PREFIX + (1 + random.nextInt(9)) + String.format("%07d", random.nextInt(10_000_000));
  }

  private TestUser register(String displayName) throws Exception {
    String phone = randomPhone();
    MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                            "phone", phone, "password", "secret123", "displayName", displayName))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return new TestUser(
            UUID.fromString(body.get("user").get("id").asText()),
            body.get("user").get("phone").asText(),
            body.get("accessToken").asText());
  }

  @Test
  void displayNameSearch_hidesPhoneUntilAddedAsContact() throws Exception {
    mockMvc.perform(get("/api/v1/users/search")
                    .param("q", nameTag)
                    .header("Authorization", "Bearer " + searcher.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(target.id().toString()))
            .andExpect(jsonPath("$[0].phone").doesNotExist());

    mockMvc.perform(post("/api/v1/contacts")
                    .header("Authorization", "Bearer " + searcher.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("phone", target.phone()))))
            .andExpect(status().isCreated());

    mockMvc.perform(get("/api/v1/users/search")
                    .param("q", nameTag)
                    .header("Authorization", "Bearer " + searcher.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].phone").value(target.phone()));
  }

  @Test
  void phoneSearch_exactMatchOnly() throws Exception {
    mockMvc.perform(get("/api/v1/users/search")
                    .param("phone", target.phone())
                    .header("Authorization", "Bearer " + searcher.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(target.id().toString()))
            .andExpect(jsonPath("$[0].phone").value(target.phone()));

    mockMvc.perform(get("/api/v1/users/search")
                    .param("phone", target.phone().substring(0, target.phone().length() - 3))
                    .header("Authorization", "Bearer " + searcher.accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
  }
}
