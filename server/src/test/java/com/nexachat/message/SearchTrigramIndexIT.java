package com.nexachat.message;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the message-search predicate is served by the V8 trigram index.
 *
 * <p>Seeds one large conversation where only a small fraction of rows match
 * the accent-insensitive pattern, then runs the exact repository predicate
 * through {@code EXPLAIN (ANALYZE)} and asserts the plan touches
 * {@code idx_msg_conv_content_trgm}. The seed is removed afterwards.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("it")
class SearchTrigramIndexIT {

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void searchPredicateUsesTheTrigramIndex() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        String directKey = "trgm-" + conversationId;
        String phone = "+8493" + String.format("%07d", (int) (Math.random() * 10_000_000));
        try {
            jdbcTemplate.update("INSERT INTO users (id, phone, password_hash, display_name)"
                    + " VALUES (?, ?, 'x', 'Trigram Indexer')", userId, phone);
            jdbcTemplate.update("INSERT INTO conversations (id, type, direct_key, created_by)"
                    + " VALUES (?, 'DIRECT', ?, ?)", conversationId, directKey, userId);
            jdbcTemplate.update("INSERT INTO conversation_members (conversation_id, user_id)"
                    + " VALUES (?, ?)", conversationId, userId);
            jdbcTemplate.update("""
                    INSERT INTO messages (conversation_id, sender_id, client_message_id, type, content)
                    SELECT ?, ?, gen_random_uuid(), 'TEXT',
                           CASE WHEN g % 300 = 0 THEN 'Nguyễn Văn A ' || g
                                ELSE 'Chào bạn, tin nhắn số ' || g END
                    FROM generate_series(1, 30000) g
                    """, conversationId, userId);
            jdbcTemplate.update("ANALYZE messages");

            String plan = String.join("\n", jdbcTemplate.queryForList("""
                    EXPLAIN (ANALYZE, TIMING OFF)
                    SELECT m.id FROM messages m
                    WHERE m.conversation_id = ?
                      AND m.type = 'TEXT'
                      AND m.deleted_at IS NULL
                      AND public.f_unaccent(m.content) ILIKE public.f_unaccent('%nguy%') ESCAPE chr(92)
                    ORDER BY m.seq DESC
                    """, String.class, conversationId));

            assertThat(plan).contains("idx_msg_conv_content_trgm");
        } finally {
            jdbcTemplate.update("DELETE FROM messages WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM conversation_members WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
