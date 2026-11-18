package com.nexachat.translate;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationService;
import com.nexachat.conversation.dto.OpenDirectRequest;
import com.nexachat.message.MessageService;
import com.nexachat.message.dto.SendMessageRequest;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A peer-suppressed row (accepted send from a user the peer blocked) must be
 * invisible to everyone but its sender on the translate paths too: the target
 * refuses {@code MESSAGE_NOT_FOUND} and the prompt context window never
 * carries the blocked content to the provider.
 */
@SpringBootTest
@ActiveProfiles("it")
class TranslationSuppressionIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private TranslationContextRepository contextRepository;
    @Autowired
    private TranslateService translateService;

    private User blocker;
    private User blocked;
    private UUID conversationId;
    private UUID suppressedMessageId;

    @BeforeEach
    void createFixture() {
        blocker = createUser("Translate blocker");
        blocked = createUser("Translate blocked");
        conversationId = conversationService
                .openDirect(blocker.getId(), new OpenDirectRequest(null, blocked.getId()))
                .body().id();
        // The blocker's own visible message must exist before the block edge:
        // after blocking, the blocker's own sends are refused with BLOCKED.
        messageService.send(blocker.getId(), conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT", "blocker own visible text",
                        null, List.of()));
        jdbcTemplate.update("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)",
                blocker.getId(), blocked.getId());
        suppressedMessageId = messageService.send(blocked.getId(), conversationId,
                new SendMessageRequest(UUID.randomUUID(), "TEXT",
                        "blocked secret needle alpha-bravo", null, List.of())).message().id();
        Boolean suppressed = jdbcTemplate.queryForObject(
                "SELECT peer_suppressed FROM messages WHERE id = ?", Boolean.class, suppressedMessageId);
        assertThat(suppressed).isTrue();
    }

    @AfterEach
    void removeFixture() {
        if (conversationId != null) {
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
        }
        for (User user : List.of(blocker, blocked)) {
            if (user != null) {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());
            }
        }
    }

    @Test
    void translateRefusesSuppressedMessageForTheBlocker() {
        assertThatThrownBy(() -> translateService.translate(blocker.getId(), suppressedMessageId, "en"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND));
    }

    @Test
    void batchRefusesSuppressedItemForTheBlocker() {
        List<com.nexachat.translate.dto.TranslateBatchItem> results = translateService.translateBatch(
                blocker.getId(), conversationId, List.of(suppressedMessageId), "en");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).error()).isEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND.getCode());
    }

    @Test
    void senderPassesTheSuppressionRefusalForTheirOwnRow() {
        // The sender can see their own suppressed row, so the refusal must be
        // a later gate (AI consent), never MESSAGE_NOT_FOUND.
        assertThatThrownBy(() -> translateService.translate(blocked.getId(), suppressedMessageId, "en"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.getCode()).isNotEqualTo(ApiErrorCode.MESSAGE_NOT_FOUND));
    }

    @Test
    void contextWindowExcludesSuppressedRowsForTheBlockerButNotForTheSender() {
        List<TranslationMessage> forBlocker = contextRepository.findContextBefore(
                conversationId, blocker.getId(), Long.MAX_VALUE, Instant.EPOCH, 10, 1_000);
        assertThat(forBlocker).extracting(TranslationMessage::content)
                .anyMatch(content -> content.contains("blocker own visible text"))
                .noneMatch(content -> content.contains("alpha-bravo"));

        List<TranslationMessage> forSender = contextRepository.findContextBefore(
                conversationId, blocked.getId(), Long.MAX_VALUE, Instant.EPOCH, 10, 1_000);
        assertThat(forSender).extracting(TranslationMessage::content)
                .anyMatch(content -> content.contains("alpha-bravo"));
    }

    private User createUser(String displayName) {
        String phone = ItPhoneRanges.nextPhone(TranslationSuppressionIT.class);
        return userRepository.save(User.create(phone, "x".repeat(60), displayName));
    }
}
