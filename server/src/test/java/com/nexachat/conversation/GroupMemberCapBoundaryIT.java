package com.nexachat.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.testutil.ItPhoneRanges;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boundary of the group member cap (cap {@value #CAP} here) across every
 * membership entry path — create, join via link, admin add, join-request
 * approval. An operation landing the group at cap-1 or exactly cap is
 * accepted; one landing at cap+1 is refused with the same
 * {@code 400 VALIDATION_FAILED} (plus the {@code limit} detail) the join
 * path defined, burning neither an invite-link use nor a pending request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupMemberCapBoundaryIT {

    private static final int CAP = 4;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        registry.add("nexachat.group.max-members", () -> String.valueOf(CAP));
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

    private record Link(String token, UUID id) {
    }

    @BeforeAll
    void cleanPreviousRuns() {
        ItPhoneRanges.clean(jdbcTemplate, GroupMemberCapBoundaryIT.class);
    }

    // -------------------------------------------------------------- create

    /**
     * Creation boundary: creator + listed members landing at cap-1 (3) or
     * exactly cap (4) is created; cap+1 (5) is refused with the limit detail
     * and no conversation row.
     */
    @ParameterizedTest
    @CsvSource({"2,201", "3,201", "4,400"})
    void createBoundary(int listedMembers, int expectedStatus) throws Exception {
        TestUser creator = createUser("CapCreate");
        List<UUID> listed = new ArrayList<>();
        for (int i = 0; i < listedMembers; i++) {
            listed.add(createUser("CapCreateMember" + i).id());
        }

        ResultActions create = postJson("/api/v1/conversations/group", creator.accessToken(),
                Map.of("title", "Cap create boundary", "memberIds", asStrings(listed)));

        if (expectedStatus == 201) {
            UUID groupId = parseId(create.andExpect(status().isCreated()).andReturn());
            assertThat(memberCount(groupId)).isEqualTo(1 + listedMembers);
        } else {
            create.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.limit").value(CAP));
        }
    }

    // ---------------------------------------------------------------- join

    /**
     * Join boundary: a link join landing the group at cap-1 (3) or exactly
     * cap (4) succeeds; a join into a full group is refused without burning
     * a link use.
     */
    @ParameterizedTest
    @CsvSource({"2,200", "3,200", "4,400"})
    void joinBoundary(int membersBefore, int expectedStatus) throws Exception {
        TestUser admin = createUser("CapJoinAdmin");
        UUID groupId = createGroup(admin, freshUsers(membersBefore - 1, "CapJoinSeed"));
        Link link = createLink(admin, groupId, false, null);
        TestUser joiner = createUser("CapJoiner");

        ResultActions join = postJson("/api/v1/invite-links/" + link.token() + "/join",
                joiner.accessToken(), null);

        if (expectedStatus == 200) {
            join.andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("JOINED"));
            assertThat(memberCount(groupId)).isEqualTo(membersBefore + 1);
        } else {
            join.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.limit").value(CAP));
            assertThat(memberCount(groupId)).isEqualTo(membersBefore);
            assertThat(useCount(link.id())).isZero();
        }
    }

    // ------------------------------------------------------ invite (add)

    /**
     * Admin-add boundary: a batch landing the group at cap-1 (3) or exactly
     * cap (4) is accepted; cap+1 — from a full group or an overshooting
     * batch — is refused with nothing inserted.
     */
    @ParameterizedTest
    @CsvSource({"2,1,204", "3,1,204", "4,1,400", "3,2,400"})
    void addMembersBoundary(int membersBefore, int addCount, int expectedStatus) throws Exception {
        TestUser admin = createUser("CapAddAdmin");
        UUID groupId = createGroup(admin, freshUsers(membersBefore - 1, "CapAddSeed"));
        List<UUID> candidates = freshUsers(addCount, "CapAddCandidate");

        ResultActions add = postJson("/api/v1/conversations/" + groupId + "/members",
                admin.accessToken(), Map.of("userIds", asStrings(candidates)));

        if (expectedStatus == 204) {
            add.andExpect(status().isNoContent());
            assertThat(memberCount(groupId)).isEqualTo(membersBefore + addCount);
        } else {
            add.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.limit").value(CAP));
            assertThat(memberCount(groupId)).isEqualTo(membersBefore);
        }
    }

    // -------------------------------------------------------------- approve

    /**
     * Approval boundary: approving a pending request while the group is at
     * cap-1 (resulting 3) or at cap-1's successor (resulting 4 = cap)
     * succeeds; approving while the group is full is refused, leaving the
     * request PENDING and the link use unconsumed.
     */
    @ParameterizedTest
    @CsvSource({"2,200", "3,200", "4,400"})
    void approveBoundary(int membersBefore, int expectedStatus) throws Exception {
        // A pending request cannot be created once the group is full, so the
        // request is seeded while there is still room and the group is then
        // filled to membersBefore before the approval fires.
        int pendingAt = Math.min(membersBefore, CAP - 1);
        TestUser admin = createUser("CapApproveAdmin");
        UUID groupId = createGroup(admin, freshUsers(pendingAt - 1, "CapApproveSeed"));
        Link link = createLink(admin, groupId, true, null);
        UUID requestId = joinRequestOf(createUser("CapApproveJoiner"), link.token());
        for (int i = pendingAt; i < membersBefore; i++) {
            postJson("/api/v1/conversations/" + groupId + "/members", admin.accessToken(),
                            Map.of("userIds", asStrings(freshUsers(1, "CapApproveFill" + i))))
                    .andExpect(status().isNoContent());
        }

        ResultActions approve = postJson(
                "/api/v1/conversations/" + groupId + "/join-requests/" + requestId + "/approve",
                admin.accessToken(), null);

        if (expectedStatus == 200) {
            approve.andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));
            assertThat(memberCount(groupId)).isEqualTo(membersBefore + 1);
        } else {
            approve.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.limit").value(CAP));
            assertThat(memberCount(groupId)).isEqualTo(membersBefore);
            assertThat(requestStatus(requestId)).isEqualTo("PENDING");
            assertThat(useCount(link.id())).isZero();
        }
    }

    // -------------------------------------------------------------- helpers

    private TestUser createUser(String label) {
        String phone = ItPhoneRanges.nextPhone(GroupMemberCapBoundaryIT.class);
        User user = User.create(phone, "hash", label);
        user = userRepository.saveAndFlush(user);
        return new TestUser(user.getId(), jwtService.generateAccessToken(user));
    }

    private List<UUID> freshUsers(int count, String label) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(createUser(label + i).id());
        }
        return ids;
    }

    private UUID createGroup(TestUser creator, List<UUID> memberIds) throws Exception {
        return parseId(postJson("/api/v1/conversations/group", creator.accessToken(),
                        Map.of("title", "Cap boundary group", "memberIds", asStrings(memberIds)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private Link createLink(TestUser admin, UUID groupId, boolean requiresApproval, Integer maxUses)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requiresApproval", requiresApproval);
        if (maxUses != null) {
            body.put("maxUses", maxUses);
        }
        MvcResult result = postJson("/api/v1/conversations/" + groupId + "/invite-link",
                admin.accessToken(), body)
                .andExpect(status().isCreated())
                .andReturn();
        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Link(node.get("token").asText(),
                UUID.fromString(node.get("id").asText()));
    }

    private UUID joinRequestOf(TestUser joiner, String token) throws Exception {
        MvcResult join = postJson("/api/v1/invite-links/" + token + "/join",
                joiner.accessToken(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        return UUID.fromString(objectMapper.readTree(join.getResponse().getContentAsString())
                .get("requestId").asText());
    }

    private UUID parseId(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    private ResultActions postJson(String path, String accessToken, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body != null ? objectMapper.writeValueAsString(body) : "{}"));
    }

    private static List<String> asStrings(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    private int memberCount(UUID groupId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ?",
                Integer.class, groupId);
    }

    private int useCount(UUID linkId) {
        return jdbcTemplate.queryForObject(
                "SELECT use_count FROM group_invite_links WHERE id = ?",
                Integer.class, linkId);
    }

    private String requestStatus(UUID requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM group_join_requests WHERE id = ?",
                String.class, requestId);
    }
}
