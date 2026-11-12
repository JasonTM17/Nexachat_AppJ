package com.nexachat.invite;

import com.nexachat.testutil.ItPhoneRanges;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adversarial review repro: a link's maxUses is enforced on direct joins but
 * never checked when a pending approval is approved, so an approval-required
 * link with maxUses=1 can admit an unbounded number of pending requesters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P1InviteApprovalMaxUsesIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
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
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;

    private record TestUser(UUID id, String accessToken) {
    }

    private TestUser alice;
    private TestUser dave;
    private TestUser bob;
    private TestUser carol;

    @BeforeAll
    void setUp() {
        ItPhoneRanges.clean(jdbcTemplate, P1InviteApprovalMaxUsesIT.class);

        alice = createTestUser("MaxUses Alice");
        dave = createTestUser("MaxUses Dave");
        bob = createTestUser("MaxUses Bob");
        carol = createTestUser("MaxUses Carol");
    }

    @Test
    void pendingApprovalsMustNotExceedTheLinkMaxUses() throws Exception {
        UUID groupId = createGroup(alice, "MaxUses Guard", List.of(dave.id()));

        MvcResult link = postJson("/api/v1/conversations/" + groupId + "/invite-link",
                alice.accessToken(), Map.of("requiresApproval", true, "maxUses", 1))
                .andExpect(status().isCreated())
                .andReturn();
        String token = objectMapper.readTree(link.getResponse().getContentAsString())
                .get("token").asText();
        UUID linkId = UUID.fromString(objectMapper.readTree(link.getResponse().getContentAsString())
                .get("id").asText());

        MvcResult bobRequest = postJson("/api/v1/invite-links/" + token + "/join",
                bob.accessToken(), null)
                .andExpect(status().isCreated())
                .andReturn();
        UUID bobRequestId = UUID.fromString(objectMapper.readTree(
                bobRequest.getResponse().getContentAsString()).get("requestId").asText());

        MvcResult carolRequest = postJson("/api/v1/invite-links/" + token + "/join",
                carol.accessToken(), null)
                .andExpect(status().isCreated())
                .andReturn();
        UUID carolRequestId = UUID.fromString(objectMapper.readTree(
                carolRequest.getResponse().getContentAsString()).get("requestId").asText());

        postJson("/api/v1/conversations/" + groupId + "/join-requests/" + bobRequestId + "/approve",
                alice.accessToken(), null)
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT use_count FROM group_invite_links WHERE id = ?", Integer.class, linkId))
                .isEqualTo(1);

        // The link is now used up (maxUses=1). Approving a second pending
        // request must not add another member or raise use_count past 1.
        postJson("/api/v1/conversations/" + groupId + "/join-requests/" + carolRequestId + "/approve",
                alice.accessToken(), null)
                .andExpect(status().isNotFound());

        Integer carolMembership = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Integer.class, groupId, carol.id());
        Integer useCount = jdbcTemplate.queryForObject(
                "SELECT use_count FROM group_invite_links WHERE id = ?", Integer.class, linkId);

        assertThat(carolMembership).isZero();
        assertThat(useCount).isEqualTo(1);
    }

    private TestUser createTestUser(String displayName) {
        String phone = ItPhoneRanges.nextPhone(P1InviteApprovalMaxUsesIT.class);
        User user = User.create(phone, "hash", displayName);
        user = userRepository.saveAndFlush(user);
        return new TestUser(user.getId(), jwtService.generateAccessToken(user));
    }

    private UUID createGroup(TestUser creator, String title, List<UUID> memberIds) throws Exception {
        List<String> ids = memberIds.stream().map(UUID::toString).toList();
        MvcResult result = postJson("/api/v1/conversations/group", creator.accessToken(),
                Map.of("title", title, "memberIds", ids))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions postJson(
            String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body != null ? objectMapper.writeValueAsString(body) : "{}"));
    }
}
