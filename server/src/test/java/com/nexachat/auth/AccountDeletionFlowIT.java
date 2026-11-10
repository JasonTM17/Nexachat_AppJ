package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.user.DeletedAccountPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountDeletionFlowIT extends SecurityITBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deleteAccountRevokesSessionsAndFreesPhoneForReregistration() throws Exception {
        String phone = "+84905553001";
        Registered victim = register(phone, "secret123");

        deleteMe(victim.accessToken(), "secret123").andExpect(status().isNoContent());

        getWithAuth("/api/v1/users/me", victim.accessToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));

        postJson("/api/v1/auth/login", Map.of("phone", phone, "password", "secret123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        Registered rereg = register(phone, "newsecret9");
        assertThat(rereg.userId()).isNotEqualTo(victim.userId());
    }

    @Test
    void wrongPasswordReturnsWrongPasswordWithoutDeleting() throws Exception {
        Registered user = register("+84905553002", "secret123");
        deleteMe(user.accessToken(), "bad-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("WRONG_PASSWORD"));

        getWithAuth("/api/v1/users/me", user.accessToken()).andExpect(status().isOk());
    }

    @Test
    void deletedUserIsExcludedFromSearch() throws Exception {
        Registered user = register("+84905553003", "secret123");
        Registered caller = register("+84905553004", "secret123");
        setDisplayName(user.userId(), "UniqueAlpha53003");

        deleteMe(user.accessToken(), "secret123").andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/search").param("q", "UniqueAlpha")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/users/search").param("phone", "+84905553003")
                        .header("Authorization", "Bearer " + caller.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void directChatHistoryRemainsVisibleToPeerWithDeletedAttribution() throws Exception {
        Registered alice = register("+84905553005", "secret123");
        Registered bob = register("+84905553006", "secret123");
        UUID conversationId = openDirect(alice, bob);

        postJson("/api/v1/conversations/" + conversationId + "/messages",
                Map.of("clientMessageId", UUID.randomUUID().toString(),
                        "type", "TEXT", "content", "before deletion"),
                alice.accessToken())
                .andExpect(status().isCreated());

        deleteMe(alice.accessToken(), "secret123").andExpect(status().isNoContent());

        var history = mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(history.getResponse().getContentAsString()).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("content").asText()).isEqualTo("before deletion");
        assertThat(items.get(0).get("senderDisplayName").asText())
                .isEqualTo(DeletedAccountPolicy.DISPLAY_NAME);
    }

    @Test
    void deletionRemovesGroupMembershipAndContacts() throws Exception {
        Registered owner = register("+84905553007", "secret123");
        Registered member = register("+84905553008", "secret123");

        postJson("/api/v1/contacts", Map.of("phone", "+84905553008"), owner.accessToken())
                .andExpect(status().isCreated());
        postJson("/api/v1/contacts", Map.of("phone", "+84905553007"), member.accessToken())
                .andExpect(status().isCreated());

        var group = postJson("/api/v1/conversations/group",
                Map.of("title", "Delete probe", "memberIds",
                        List.of(member.userId().toString())),
                owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        UUID groupId = UUID.fromString(
                objectMapper.readTree(group.getResponse().getContentAsString()).get("id").asText());

        deleteMe(member.accessToken(), "secret123").andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM contacts WHERE owner_id = ? OR contact_user_id = ?",
                Integer.class, member.userId(), member.userId())).isEqualTo(0);
        assertThat(memberCount(groupId, member.userId())).isEqualTo(0);
    }

    @Test
    void soleAdminDeletionPromotesEarliestRemainingMember() throws Exception {
        Registered admin = register("+84905553009", "secret123");
        Registered early = register("+84905553010", "secret123");
        Registered late = register("+84905553011", "secret123");

        UUID groupId = createGroup(admin, List.of(early.userId(), late.userId()));
        jdbcTemplate.update(
                "UPDATE conversation_members SET joined_at = ? WHERE conversation_id = ? AND user_id = ?",
                java.sql.Timestamp.from(Instant.parse("2019-06-01T00:00:00Z")),
                groupId, early.userId());
        jdbcTemplate.update(
                "UPDATE conversation_members SET joined_at = ? WHERE conversation_id = ? AND user_id = ?",
                java.sql.Timestamp.from(Instant.parse("2019-06-02T00:00:00Z")),
                groupId, late.userId());

        deleteMe(admin.accessToken(), "secret123").andExpect(status().isNoContent());

        assertThat(memberRole(groupId, early.userId())).isEqualTo("ADMIN");
        assertThat(memberRole(groupId, late.userId())).isEqualTo("MEMBER");
        assertThat(memberCount(groupId, admin.userId())).isZero();
        assertThat(adminCount(groupId)).isEqualTo(1);
        assertThat(systemMessages(groupId, early).stream()
                .map(n -> n.get("content").asText())
                .anyMatch(text -> text.contains("group admin"))).isTrue();
    }

    @Test
    void promotionTieBreaksOnUserIdWhenJoinedAtEqual() throws Exception {
        Registered admin = register("+84905553012", "secret123");
        Registered userA = register("+84905553013", "secret123");
        Registered userB = register("+84905553014", "secret123");

        UUID groupId = createGroup(admin, List.of(userA.userId(), userB.userId()));
        Instant sameJoin = Instant.parse("2020-01-01T00:00:00Z");
        jdbcTemplate.update(
                "UPDATE conversation_members SET joined_at = ? WHERE conversation_id = ? AND user_id IN (?, ?)",
                java.sql.Timestamp.from(sameJoin), groupId, userA.userId(), userB.userId());

        deleteMe(admin.accessToken(), "secret123").andExpect(status().isNoContent());

        UUID expectedAdmin = userA.userId().compareTo(userB.userId()) < 0 ? userA.userId() : userB.userId();
        UUID expectedMember = expectedAdmin.equals(userA.userId()) ? userB.userId() : userA.userId();
        assertThat(memberRole(groupId, expectedAdmin)).isEqualTo("ADMIN");
        assertThat(memberRole(groupId, expectedMember)).isEqualTo("MEMBER");
    }

    @Test
    void soleAdminDeletionWhenLastMemberLeavesEmptyGroup() throws Exception {
        Registered admin = register("+84905553015", "secret123");
        Registered member = register("+84905553024", "secret123");
        UUID groupId = createGroup(admin, List.of(member.userId()));
        deleteMe(member.accessToken(), "secret123").andExpect(status().isNoContent());

        deleteMe(admin.accessToken(), "secret123").andExpect(status().isNoContent());

        assertThat(memberCount(groupId, admin.userId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_members WHERE conversation_id = ?",
                Integer.class, groupId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversations WHERE id = ?", Integer.class, groupId)).isEqualTo(1);
    }

    @Test
    void soleAdminDeletionHandOverInTwoGroupsAtOnce() throws Exception {
        Registered admin = register("+84905553016", "secret123");
        Registered g1Member = register("+84905553017", "secret123");
        Registered g2Member = register("+84905553018", "secret123");

        UUID groupOne = createGroup(admin, List.of(g1Member.userId()));
        UUID groupTwo = createGroup(admin, List.of(g2Member.userId()));

        deleteMe(admin.accessToken(), "secret123").andExpect(status().isNoContent());

        assertThat(memberRole(groupOne, g1Member.userId())).isEqualTo("ADMIN");
        assertThat(memberRole(groupTwo, g2Member.userId())).isEqualTo("ADMIN");
        assertThat(adminCount(groupOne)).isEqualTo(1);
        assertThat(adminCount(groupTwo)).isEqualTo(1);
    }

    @Test
    void concurrentDeletionOfTwoAdminsStillLeavesAGroupAdmin() throws Exception {
        Registered adminA = register("+84905553019", "secret123");
        Registered adminB = register("+84905553020", "secret123");
        Registered member = register("+84905553021", "secret123");

        UUID groupId = createGroup(adminA, List.of(adminB.userId(), member.userId()));
        jdbcTemplate.update(
                "UPDATE conversation_members SET role = 'ADMIN' WHERE conversation_id = ? AND user_id = ?",
                groupId, adminB.userId());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstStatus = executor.submit(() -> {
                ready.countDown();
                start.await();
                return deleteMe(adminA.accessToken(), "secret123")
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> secondStatus = executor.submit(() -> {
                ready.countDown();
                start.await();
                return deleteMe(adminB.accessToken(), "secret123")
                        .andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(firstStatus.get(30, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(secondStatus.get(30, TimeUnit.SECONDS)).isEqualTo(204);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Integer.class, groupId, member.userId())).isEqualTo(1);
        assertThat(memberRole(groupId, member.userId())).isEqualTo("ADMIN");
        assertThat(adminCount(groupId)).isEqualTo(1);
    }

    @Test
    void inAppSelfLeaveStillRejectsSoleAdminWithOtherMembers() throws Exception {
        Registered admin = register("+84905553022", "secret123");
        Registered member = register("+84905553023", "secret123");
        UUID groupId = createGroup(admin, List.of(member.userId()));

        postJson("/api/v1/conversations/" + groupId + "/leave", Map.of(), admin.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(memberCount(groupId, admin.userId())).isEqualTo(1);
        assertThat(memberRole(groupId, admin.userId())).isEqualTo("ADMIN");
    }

    private ResultActions deleteMe(String accessToken, String password) throws Exception {
        return mockMvc.perform(delete("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("password", password))));
    }

    private UUID openDirect(Registered a, Registered b) throws Exception {
        var result = postJson("/api/v1/conversations/direct",
                Map.of("userId", b.userId().toString()), a.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createGroup(Registered creator, List<UUID> memberIds) throws Exception {
        var result = postJson("/api/v1/conversations/group",
                Map.of("title", "Acct del " + UUID.randomUUID(), "memberIds",
                        memberIds.stream().map(UUID::toString).toList()),
                creator.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void setDisplayName(UUID userId, String name) {
        jdbcTemplate.update("UPDATE users SET display_name = ? WHERE id = ?", name, userId);
    }

    private int memberCount(UUID conversationId, UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Integer.class, conversationId, userId);
    }

    private String memberRole(UUID conversationId, UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT role FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                String.class, conversationId, userId);
    }

    private int adminCount(UUID conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_members WHERE conversation_id = ? AND role = 'ADMIN'",
                Integer.class, conversationId);
    }

    private List<JsonNode> systemMessages(UUID conversationId, Registered reader) throws Exception {
        var result = mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + reader.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        List<JsonNode> system = new java.util.ArrayList<>();
        for (JsonNode item : items) {
            if ("SYSTEM".equals(item.get("type").asText())) {
                system.add(item);
            }
        }
        return system;
    }
}
