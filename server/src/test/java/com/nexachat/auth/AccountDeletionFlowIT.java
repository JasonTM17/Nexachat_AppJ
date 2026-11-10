package com.nexachat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.user.DeletedAccountPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    void soleAdminCannotDeleteWhileOtherMembersRemain() throws Exception {
        Registered admin = register("+84905553009", "secret123");
        Registered member = register("+84905553010", "secret123");
        postJson("/api/v1/conversations/group",
                Map.of("title", "Admin trap", "memberIds", List.of(member.userId().toString())),
                admin.accessToken())
                .andExpect(status().isCreated());

        deleteMe(admin.accessToken(), "secret123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
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

    private void setDisplayName(UUID userId, String name) {
        jdbcTemplate.update("UPDATE users SET display_name = ? WHERE id = ?", name, userId);
    }

    private int memberCount(UUID conversationId, UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Integer.class, conversationId, userId);
    }
}
