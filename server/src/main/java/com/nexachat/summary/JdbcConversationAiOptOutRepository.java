package com.nexachat.summary;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Persists the per-conversation AI opt-out and purges derived data when a
 * member opts out: stored message vectors and cached ask answers that cite the
 * member's messages are removed in the same transaction. A database-less test
 * context (the {@code test} profile) gets empty read results.
 */
@Repository
public class JdbcConversationAiOptOutRepository implements ConversationAiOptOuts {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public JdbcConversationAiOptOutRepository(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    @Override
    public Set<UUID> optedOutUserIds(UUID conversationId) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return Set.of();
        }
        List<UUID> userIds = jdbcTemplate.queryForList(
                "SELECT user_id FROM conversation_ai_opt_outs WHERE conversation_id = ?",
                UUID.class, conversationId);
        return Set.copyOf(userIds);
    }

    @Override
    public Set<UUID> optedOutConversationIds(UUID userId) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return Set.of();
        }
        List<UUID> conversationIds = jdbcTemplate.queryForList(
                "SELECT conversation_id FROM conversation_ai_opt_outs WHERE user_id = ?",
                UUID.class, userId);
        return Set.copyOf(conversationIds);
    }

    @Override
    public boolean hasOptedOutMessages(UUID conversationId) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return false;
        }
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM conversation_ai_opt_outs o
                    JOIN messages m
                      ON m.conversation_id = o.conversation_id
                     AND m.sender_id = o.user_id
                    WHERE o.conversation_id = ?
                      AND m.type = 'TEXT'
                      AND m.deleted_at IS NULL
                      AND m.content IS NOT NULL
                      AND btrim(m.content) <> ''
                )
                """, Boolean.class, conversationId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public boolean isOptedOut(UUID conversationId, UUID userId) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return false;
        }
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM conversation_ai_opt_outs
                    WHERE conversation_id = ? AND user_id = ?
                )
                """, Boolean.class, conversationId, userId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Inserts the opt-out idempotently and removes every derived artifact in
     * the same transaction: stored vectors for the member's messages in this
     * conversation and cached ask answers that cite any of them.
     */
    @Transactional
    public void optOut(UUID conversationId, UUID userId) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        jdbcTemplate.update("""
                INSERT INTO conversation_ai_opt_outs (conversation_id, user_id)
                VALUES (?, ?)
                ON CONFLICT (conversation_id, user_id) DO NOTHING
                """, conversationId, userId);
        jdbcTemplate.update("""
                DELETE FROM message_embeddings e
                USING messages m
                WHERE e.message_id = m.id
                  AND m.conversation_id = ?
                  AND m.sender_id = ?
                """, conversationId, userId);
        jdbcTemplate.update("""
                DELETE FROM rag_ask_cache c
                USING messages m
                WHERE c.conversation_id = ?
                  AND m.conversation_id = c.conversation_id
                  AND m.sender_id = ?
                  AND m.id = ANY (c.cited_message_ids)
                """, conversationId, userId);
    }

    @Transactional
    public void clearOptOut(UUID conversationId, UUID userId) {
        requireJdbcTemplate().update("""
                DELETE FROM conversation_ai_opt_outs
                WHERE conversation_id = ? AND user_id = ?
                """, conversationId, userId);
    }

    private JdbcTemplate requireJdbcTemplate() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("A database is required to change the AI opt-out state");
        }
        return jdbcTemplate;
    }
}
