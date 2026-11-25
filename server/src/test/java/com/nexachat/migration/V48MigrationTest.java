package com.nexachat.migration;

import com.nexachat.rag.JdbcRagMessageIndex;
import com.nexachat.rag.RagMessage;
import com.nexachat.testutil.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V48 lets one message hold one embedding row per model identity. */
class V48MigrationTest {

    @Test
    void messageEmbeddingsUseCompositeMessageModelPrimaryKey() {
        try (PostgresTestDatabase database = PostgresTestDatabase.create("mig-v48-model-key")) {
            Flyway flyway = Flyway.configure()
                    .dataSource(database.jdbcUrl(), database.username(), database.password())
                    .load();
            flyway.migrate();
            flyway.validate();

            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            List<String> pkColumns = jdbc.queryForList("""
                    SELECT kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON kcu.constraint_name = tc.constraint_name
                     AND kcu.table_schema = tc.table_schema
                     AND kcu.table_name = tc.table_name
                    WHERE tc.table_schema = 'public'
                      AND tc.table_name = 'message_embeddings'
                      AND tc.constraint_type = 'PRIMARY KEY'
                    ORDER BY kcu.ordinal_position
                    """, String.class);
            assertThat(pkColumns).containsExactly("message_id", "model");

            UUID user = UUID.randomUUID();
            UUID conversation = UUID.randomUUID();
            UUID message = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, phone, password_hash, display_name) VALUES (?, ?, 'x', 'A')",
                    user, "+84948000001");
            jdbc.update("INSERT INTO conversations (id, type, direct_key, created_by) VALUES (?, 'DIRECT', 'a:b', ?)",
                    conversation, user);
            jdbc.update("""
                    INSERT INTO messages (id, conversation_id, sender_id, client_message_id, type, content)
                    VALUES (?, ?, ?, ?, 'TEXT', 'fixture')
                    """, message, conversation, user, UUID.randomUUID());

            RagMessage ragMessage = new RagMessage(message, conversation, 1L, user, "A",
                    "fixture", Instant.now(), null, null);
            JdbcRagMessageIndex index = new JdbcRagMessageIndex(jdbc);
            String hash = "a".repeat(64);
            index.saveVectors("lexical", 1, List.of(ragMessage),
                    Map.of(message, new float[]{1.0f}), Map.of(message, hash));
            index.saveVectors("external-model", 1, List.of(ragMessage),
                    Map.of(message, new float[]{2.0f}), Map.of(message, hash));

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM message_embeddings WHERE message_id = ?",
                    Integer.class, message)).isEqualTo(2);
            assertThat(index.loadVectors("lexical", List.of(message)).get(message))
                    .containsExactly(1.0f);
            assertThat(index.loadVectors("external-model", List.of(message)).get(message))
                    .containsExactly(2.0f);

            index.saveVectors("lexical", 1, List.of(ragMessage),
                    Map.of(message, new float[]{3.0f}), Map.of(message, hash));
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM message_embeddings WHERE message_id = ?",
                    Integer.class, message)).isEqualTo(2);
            assertThat(index.loadVectors("lexical", List.of(message)).get(message))
                    .containsExactly(3.0f);
            assertThat(index.loadVectors("external-model", List.of(message)).get(message))
                    .containsExactly(2.0f);

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO message_embeddings
                        (message_id, conversation_id, model, dim, vector, content_hash, created_at)
                    VALUES (?, ?, 'lexical', 1, decode(repeat('00', 4), 'hex'), ?, now())
                    """, message, conversation, "b".repeat(64)))
                    .isInstanceOf(DuplicateKeyException.class);
        }
    }
}
