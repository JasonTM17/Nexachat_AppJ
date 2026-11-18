package com.nexachat.rag;

import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.testutil.ItPhoneRanges;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cached ask answer records a hash over its cited messages. Retrieval builds
 * that hash from {@link RagMessage#editedAt()} instants while validation reloads
 * it from the database; both sides must render edited_at identically or every
 * citation set containing an edited message misses the cache forever.
 */
@SpringBootTest
@ActiveProfiles("it")
class RagCitationHashIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private RagQuestionCache questionCache;

    private User caller;
    private UUID conversationId;

    @BeforeEach
    void createFixture() {
        caller = userRepository.save(
                User.create(ItPhoneRanges.nextPhone(RagCitationHashIT.class),
                        "x".repeat(60), "Rag citation caller"));
        Conversation conversation = conversationRepository.save(
                Conversation.group(caller, "Rag citation hash fixture"));
        conversationId = conversation.getId();
    }

    @AfterEach
    void removeFixture() {
        if (conversationId != null) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        if (caller != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", caller.getId());
        }
    }

    @Test
    void citationsOfAnEditedMessageStayValid() {
        Instant editedAt = Instant.parse("2026-01-02T03:04:05.123456Z");
        RagMessage message = insertMessage("The release ships on Friday.", editedAt);

        String citedHash = RagHashes.citationsHash(List.of(message));

        assertThat(questionCache.citationsValid(List.of(message.id()), citedHash))
                .as("edited_at must hash identically at write and validation time")
                .isTrue();
    }

    @Test
    void citationsOfAnUneditedMessageStayValid() {
        RagMessage message = insertMessage("The release ships on Friday.", null);

        String citedHash = RagHashes.citationsHash(List.of(message));

        assertThat(questionCache.citationsValid(List.of(message.id()), citedHash)).isTrue();
    }

    private RagMessage insertMessage(String content, Instant editedAt) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        jdbcTemplate.update("""
                INSERT INTO messages
                    (id, conversation_id, sender_id, client_message_id, type, content,
                     created_at, edited_at)
                VALUES (?, ?, ?, ?, 'TEXT', CAST(? AS text), ?, ?)
                """, id, conversationId, caller.getId(), UUID.randomUUID(), content,
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
                editedAt == null ? null : OffsetDateTime.ofInstant(editedAt, ZoneOffset.UTC));
        return new RagMessage(id, conversationId, 1L, caller.getId(), "Rag citation caller",
                content, createdAt, editedAt, null);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}