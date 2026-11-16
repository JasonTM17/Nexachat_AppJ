package com.nexachat.translate;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Reads the bounded context window used for pronoun/tone grounding plus the
 * target-message rows themselves. Everything runs through narrow projections —
 * no entity graph, and the same visibility contract as message reading:
 * sent after the member's admission.
 */
@Repository
@Profile("!test")
public class TranslationContextRepository {

    private static final RowMapper<TranslationMessage> MESSAGE_MAPPER = (rs, rowNum) ->
            new TranslationMessage(
                    rs.getObject("id", UUID.class),
                    rs.getLong("seq"),
                    rs.getObject("sender_id", UUID.class),
                    rs.getString("sender_name"),
                    rs.getString("content"),
                    rs.getLong("change_version"),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<TargetRow> TARGET_MAPPER = (rs, rowNum) -> {
        java.sql.Timestamp deletedAt = rs.getTimestamp("deleted_at");
        return new TargetRow(
                rs.getObject("id", UUID.class),
                rs.getLong("seq"),
                rs.getObject("sender_id", UUID.class),
                rs.getString("sender_name"),
                rs.getString("content"),
                rs.getLong("change_version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("type"),
                deletedAt == null ? null : deletedAt.toInstant(),
                rs.getString("encryption"));
    };

    private final JdbcTemplate jdbcTemplate;

    public TranslationContextRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * The most recent {@code limit} usable text messages in the conversation
     * strictly before {@code beforeSeq}, excluding messages authored by
     * AI-opted-out members. Chronological order, per-message text truncated
     * to {@code maxCharsPerMessage}.
     */
    public List<TranslationMessage> findContextBefore(
            UUID conversationId, long beforeSeq, Instant admittedFrom, int limit, int maxCharsPerMessage) {
        List<TranslationMessage> newestFirst = jdbcTemplate.query("""
                SELECT m.id, m.seq, m.sender_id, u.display_name AS sender_name,
                       m.content, m.change_version, m.created_at
                FROM messages m
                JOIN users u ON u.id = m.sender_id
                WHERE m.conversation_id = ?
                  AND m.seq < ?
                  AND m.type = 'TEXT'
                  AND m.deleted_at IS NULL
                  AND (m.expires_at IS NULL OR m.expires_at > now())
                  AND m.encryption IS NULL
                  AND m.content IS NOT NULL
                  AND btrim(m.content) <> ''
                  AND m.created_at >= ?
                  AND NOT EXISTS (
                      SELECT 1 FROM conversation_ai_opt_outs o
                      WHERE o.conversation_id = m.conversation_id
                        AND o.user_id = m.sender_id
                  )
                ORDER BY m.seq DESC
                LIMIT ?
                """,
                (rs, rowNum) -> {
                    TranslationMessage message = MESSAGE_MAPPER.mapRow(rs, rowNum);
                    return new TranslationMessage(message.id(), message.seq(), message.senderId(),
                            message.senderName(), truncate(message.content(), maxCharsPerMessage),
                            message.changeVersion(), message.createdAt());
                },
                conversationId, beforeSeq,
                OffsetDateTime.ofInstant(admittedFrom, ZoneOffset.UTC), limit);
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    /**
     * Raw validation view of the requested messages: type, deletion and
     * encryption state plus the sender id for the opt-out check. Deleted and
     * encrypted rows are returned too so the caller can refuse them with a
     * typed error instead of a generic not-found.
     */
    public List<TargetRow> findTargets(UUID conversationId, List<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(messageIds.size(), "?"));
        Object[] args = new Object[messageIds.size() + 1];
        args[0] = conversationId;
        for (int i = 0; i < messageIds.size(); i++) {
            args[i + 1] = messageIds.get(i);
        }
        return jdbcTemplate.query(
                "SELECT m.id, m.seq, m.sender_id, u.display_name AS sender_name,"
                        + " m.content, m.change_version, m.created_at,"
                        + " m.type, m.deleted_at, m.encryption"
                        + " FROM messages m JOIN users u ON u.id = m.sender_id"
                        + " WHERE m.conversation_id = ? AND m.id IN (" + placeholders + ")",
                TARGET_MAPPER,
                args);
    }

    /** Raw validation view of a message before the translatable filters apply. */
    public record TargetRow(
            UUID id,
            long seq,
            UUID senderId,
            String senderName,
            String content,
            long changeVersion,
            Instant createdAt,
            String type,
            Instant deletedAt,
            String encryption) {

        public TranslationMessage toMessage() {
            return new TranslationMessage(id, seq, senderId, senderName, content,
                    changeVersion, createdAt);
        }
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= maxChars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxChars));
    }
}
