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
 * member opts out: stored message vectors, cached ask answers and cached
 * summaries of the conversation are removed in the same transaction. A
 * database-less test context (the {@code test} profile) gets empty read
 * results.
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
                      AND (m.expires_at IS NULL OR m.expires_at > now())
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
     * conversation, every cached ask answer and every cached summary for the
     * conversation. The cache rows record only the cited messages, while the
     * external prompt can use the member's messages as uncited context (and
     * the summary overview is never citation-bound), so the whole
     * conversation's caches are the smallest set that is guaranteed to be
     * free of it.
     *
     * @return the member's message ids in the conversation, so listeners can
     *         purge the same set from in-memory caches
     */
    @Transactional
    public Set<UUID> optOut(UUID conversationId, UUID userId) {
        JdbcTemplate jdbcTemplate = requireJdbcTemplate();
        Set<UUID> messageIds = Set.copyOf(jdbcTemplate.queryForList("""
                SELECT m.id FROM messages m
                WHERE m.conversation_id = ? AND m.sender_id = ?
                """, UUID.class, conversationId, userId));
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
                DELETE FROM rag_ask_cache
                WHERE conversation_id = ?
                """, conversationId);
        // Cached summaries are derived data too: the LLM overview field carries
        // no citations, so a cached row can paraphrase the member's messages
        // even when no cited message belongs to them. Purge the whole
        // conversation's summary cache, like the ask cache above.
        jdbcTemplate.update("""
                DELETE FROM message_summaries
                WHERE conversation_id = ?
                """, conversationId);
        // Cached translations of the member's messages are derived data too:
        // they were generated from text the member now keeps out of AI.
        jdbcTemplate.update("""
                DELETE FROM message_translations t
                USING messages m
                WHERE t.message_id = m.id
                  AND m.conversation_id = ?
                  AND m.sender_id = ?
                """, conversationId, userId);
        return messageIds;
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
