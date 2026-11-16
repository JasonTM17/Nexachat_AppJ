package com.nexachat.translate;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Caller-scoped auto-translate preference per conversation (V37). */
@Repository
@Profile("!test")
public class ConversationTranslatePrefsRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConversationTranslatePrefsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Prefs> find(UUID conversationId, UUID userId) {
        List<Prefs> rows = jdbcTemplate.query("""
                SELECT auto_translate, target_lang
                FROM conversation_translate_prefs
                WHERE conversation_id = ? AND user_id = ?
                """,
                (rs, rowNum) -> new Prefs(rs.getBoolean("auto_translate"),
                        rs.getString("target_lang")),
                conversationId, userId);
        return rows.stream().findFirst();
    }

    public void upsert(UUID conversationId, UUID userId, boolean autoTranslate, String targetLang) {
        jdbcTemplate.update("""
                INSERT INTO conversation_translate_prefs
                    (conversation_id, user_id, auto_translate, target_lang, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (conversation_id, user_id)
                DO UPDATE SET auto_translate = EXCLUDED.auto_translate,
                              target_lang = EXCLUDED.target_lang,
                              updated_at = now()
                """, conversationId, userId, autoTranslate, targetLang);
    }

    /** {@code targetLang} null falls back to the user-level default. */
    public record Prefs(boolean autoTranslate, String targetLang) {
    }
}
