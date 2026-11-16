package com.nexachat.migration;

import com.nexachat.testutil.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V37 adds the translation cache, glossary, translate prefs and rate limits. */
class V37MigrationTest {

    @Test
    void migrateEmptyDatabaseCreatesTranslationSchema() {
        try (PostgresTestDatabase database = PostgresTestDatabase.create("mig-v37-schema")) {
            Flyway flyway = Flyway.configure()
                    .dataSource(database.jdbcUrl(), database.username(), database.password())
                    .load();
            flyway.migrate();
            flyway.validate();

            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            List<String> tables = jdbc.queryForList("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                    """, String.class);
            assertThat(tables).contains("message_translations",
                    "translation_glossary_terms", "conversation_translate_prefs",
                    "translate_request_limits");

            // users.translate_target_lang exists and is nullable (client default)
            Integer nullable = jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = 'users' AND column_name = 'translate_target_lang'
                      AND is_nullable = 'YES'
                    """, Integer.class);
            assertThat(nullable).isEqualTo(1);
        }
    }

    @Test
    void v37TablesEnforceConstraintsAndCascade() {
        try (PostgresTestDatabase database = PostgresTestDatabase.create("mig-v37-data")) {
            Flyway.configure()
                    .dataSource(database.jdbcUrl(), database.username(), database.password())
                    .load()
                    .migrate();

            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            UUID user = UUID.randomUUID();
            UUID peer = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, phone, password_hash, display_name)"
                            + " VALUES (?, ?, 'x', ?)", user, "+84937000001", "Ann");
            jdbc.update("INSERT INTO users (id, phone, password_hash, display_name)"
                            + " VALUES (?, ?, 'x', ?)", peer, "+84937000002", "Bo");
            UUID conversation = UUID.randomUUID();
            jdbc.update("INSERT INTO conversations (id, type, title, created_by)"
                            + " VALUES (?, 'GROUP', 'test group', ?)", conversation, user);
            jdbc.update("INSERT INTO conversation_members (conversation_id, user_id, role)"
                            + " VALUES (?, ?, 'MEMBER'), (?, ?, 'MEMBER')",
                    conversation, user, conversation, peer);
            UUID message = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO messages (id, conversation_id, sender_id, client_message_id, type, content)
                    VALUES (?, ?, ?, ?, 'TEXT', ?)
                    """, message, conversation, peer, UUID.randomUUID(), "xin chào");

            // cache row round-trips
            jdbc.update("""
                    INSERT INTO message_translations
                        (message_id, change_version, target_lang, glossary_hash,
                         source_lang, translated_text, model, provider, expires_at)
                    VALUES (?, 0, 'en', ?, 'vi', 'hello', 'm', 'primary', now() + interval '1 day')
                    """, message, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
            assertThat(jdbc.queryForObject(
                    "SELECT translated_text FROM message_translations WHERE message_id = ?",
                    String.class, message)).isEqualTo("hello");

            // provider check rejects unknown labels
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO message_translations
                        (message_id, change_version, target_lang, glossary_hash,
                         source_lang, translated_text, model, provider, expires_at)
                    VALUES (?, 1, 'en', ?, 'vi', 'x', 'm', 'bogus', now() + interval '1 day')
                    """, message, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
                    .isInstanceOf(Exception.class);

            // blank glossary terms rejected; NULL replacement allowed (keep verbatim)
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO translation_glossary_terms
                        (conversation_id, user_id, term) VALUES (?, ?, '  ')
                    """, conversation, user))
                    .isInstanceOf(Exception.class);
            jdbc.update("""
                    INSERT INTO translation_glossary_terms
                        (conversation_id, user_id, term) VALUES (?, ?, 'NexaChat')
                    """, conversation, user);

            // prefs upsert + defaults
            jdbc.update("""
                    INSERT INTO conversation_translate_prefs
                        (conversation_id, user_id, auto_translate, target_lang)
                    VALUES (?, ?, TRUE, 'en')
                    """, conversation, user);
            assertThat(jdbc.queryForObject("""
                    SELECT auto_translate FROM conversation_translate_prefs
                    WHERE conversation_id = ? AND user_id = ?
                    """, Boolean.class, conversation, user)).isTrue();

            // message delete cascades the cache row
            jdbc.update("DELETE FROM messages WHERE id = ?", message);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM message_translations WHERE message_id = ?",
                    Integer.class, message)).isZero();
        }
    }
}
