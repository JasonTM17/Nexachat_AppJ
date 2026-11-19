package com.nexachat.translate;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    private static final int EXPIRED_SWEEP_BATCH = 50;

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
     *
     * <p>Transactional in its own right: the callers no longer hold a
     * transaction across the provider call, so the insert, the cap trim and
     * the expired sweep below must commit or roll back as one unit.
     */
    @Transactional
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
        purgeExpiredBatch();
    }

    /** Drops every cached translation of one message (edit, recall, opt-out). */
    public void deleteByMessageId(UUID messageId) {
        jdbcTemplate.update("DELETE FROM message_translations WHERE message_id = ?", messageId);
    }

    /**
     * Bounded sweep of expired rows, run on each cache write so the TTL is
     * enforced without a dedicated scheduler (which would wake a scaled-to-zero
     * database on its own).
     */
    private void purgeExpiredBatch() {
        jdbcTemplate.update("""
                DELETE FROM message_translations
                WHERE ctid IN (
                    SELECT ctid FROM message_translations
                    WHERE expires_at <= now()
                    LIMIT ?
                )
                """, EXPIRED_SWEEP_BATCH);
    }

    public record CacheEntry(String translatedText, String sourceLang,
                             String model, String provider) {
    }
}
