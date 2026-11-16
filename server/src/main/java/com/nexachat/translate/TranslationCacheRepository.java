package com.nexachat.translate;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-through cache for generated translations (V37). The key folds in the
 * message's sync revision and the caller's glossary hash, so edits and
 * glossary changes make old rows unreachable without a backfill; the
 * invalidation listener still deletes eagerly on edit/recall so recalled
 * text never lingers in the cache.
 */
@Repository
@Profile("!test")
public class TranslationCacheRepository {

    private static final String HIT_SQL = """
            SELECT translated_text, source_lang, model, provider
            FROM message_translations
            WHERE message_id = ? AND change_version = ? AND target_lang = ?
              AND glossary_hash = ? AND expires_at > now()
            """;

    private final JdbcTemplate jdbcTemplate;

    public TranslationCacheRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CacheEntry> find(UUID messageId, long changeVersion,
                                     String targetLang, String glossaryHash) {
        List<CacheEntry> entries = jdbcTemplate.query(
                HIT_SQL,
                (rs, rowNum) -> new CacheEntry(
                        rs.getString("translated_text"),
                        rs.getString("source_lang"),
                        rs.getString("model"),
                        rs.getString("provider")),
                messageId, changeVersion, targetLang, glossaryHash);
        return entries.stream().findFirst();
    }

    /**
     * Insert-or-replace plus per-message language cap. The cap keeps the
     * newest {@code maxLangsPerMessage} rows of the current revision; older
     * revisions are removed wholesale because the invalidation listener may
     * have already deleted them.
     */
    public void save(UUID messageId, long changeVersion, String targetLang,
                     String glossaryHash, CacheEntry entry, Instant expiresAt,
                     int maxLangsPerMessage) {
        jdbcTemplate.update("""
                INSERT INTO message_translations
                    (message_id, change_version, target_lang, glossary_hash,
                     source_lang, translated_text, model, provider, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (message_id, change_version, target_lang, glossary_hash)
                DO UPDATE SET translated_text = EXCLUDED.translated_text,
                              source_lang = EXCLUDED.source_lang,
                              model = EXCLUDED.model,
                              provider = EXCLUDED.provider,
                              created_at = now(),
                              expires_at = EXCLUDED.expires_at
                """,
                messageId, changeVersion, targetLang, glossaryHash,
                entry.sourceLang(), entry.translatedText(), entry.model(), entry.provider(),
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        jdbcTemplate.update("""
                DELETE FROM message_translations t
                WHERE t.message_id = ?
                  AND (t.change_version <> ?
                       OR (t.change_version, t.target_lang, t.glossary_hash) NOT IN (
                           SELECT change_version, target_lang, glossary_hash
                           FROM message_translations
                           WHERE message_id = ? AND change_version = ?
                           ORDER BY created_at DESC
                           LIMIT ?
                       ))
                """,
                messageId, changeVersion, messageId, changeVersion, maxLangsPerMessage);
    }

    /** Drops every cached translation of one message (edit, recall, opt-out). */
    public void deleteByMessageId(UUID messageId) {
        jdbcTemplate.update("DELETE FROM message_translations WHERE message_id = ?", messageId);
    }

    /** Drops cached translations of every message authored by {@code senderId} in the conversation. */
    public void deleteByConversationAndSender(UUID conversationId, UUID senderId) {
        jdbcTemplate.update("""
                DELETE FROM message_translations t
                USING messages m
                WHERE t.message_id = m.id
                  AND m.conversation_id = ?
                  AND m.sender_id = ?
                """, conversationId, senderId);
    }

    /** Best-effort expired-row sweep; the retention job does the periodic bulk delete. */
    public int deleteExpired() {
        return jdbcTemplate.update("DELETE FROM message_translations WHERE expires_at <= now()");
    }

    public record CacheEntry(String translatedText, String sourceLang,
                             String model, String provider) {
    }
}
