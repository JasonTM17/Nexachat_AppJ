package com.nexachat.translate;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Per-member per-conversation glossary persistence (V37). */
@Repository
@Profile("!test")
public class TranslationGlossaryRepository {

    private final JdbcTemplate jdbcTemplate;

    public TranslationGlossaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<GlossaryTerm> findTerms(UUID conversationId, UUID userId) {
        return jdbcTemplate.query("""
                SELECT term, replacement
                FROM translation_glossary_terms
                WHERE conversation_id = ? AND user_id = ?
                ORDER BY lower(term), term
                """,
                (rs, rowNum) -> new GlossaryTerm(rs.getString("term"), rs.getString("replacement")),
                conversationId, userId);
    }

    /**
     * Replaces the caller's glossary atomically: terms absent from
     * {@code terms} are removed, the rest upserted. Runs inside the service's
     * transaction so a half-written glossary never reaches a prompt.
     */
    @Transactional
    public void replaceTerms(UUID conversationId, UUID userId, List<GlossaryTerm> terms) {
        jdbcTemplate.update("""
                DELETE FROM translation_glossary_terms
                WHERE conversation_id = ? AND user_id = ?
                """, conversationId, userId);
        for (GlossaryTerm term : terms) {
            jdbcTemplate.update("""
                    INSERT INTO translation_glossary_terms
                        (conversation_id, user_id, term, replacement, updated_at)
                    VALUES (?, ?, ?, ?, now())
                    """, conversationId, userId, term.term(), term.replacement());
        }
    }

    /** One validated glossary entry: {@code replacement} null keeps the term verbatim. */
    public record GlossaryTerm(String term, String replacement) {
    }
}
