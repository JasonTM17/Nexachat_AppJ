package com.nexachat.summary;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Repository
@Profile("!test")
public class SummaryWindowRepository {

    private static final int MAX_MESSAGES = 500;
    private static final int MAX_MESSAGE_CHARACTERS = 1_000;

    private static final String SELECT_MESSAGES = """
            SELECT m.id, m.seq, m.sender_id, u.display_name AS sender_name,
                   m.content, m.created_at, m.edited_at
            FROM messages m
            JOIN users u ON u.id = m.sender_id
            WHERE m.conversation_id = ?
              AND m.type = 'TEXT'
              AND m.deleted_at IS NULL
              AND m.content IS NOT NULL
              AND btrim(m.content) <> ''
              AND NOT EXISTS (
                  SELECT 1 FROM message_attachments a WHERE a.message_id = m.id
              )
            """;

    private static final RowMapper<SummaryMessage> MESSAGE_MAPPER = (rs, rowNum) -> {
        Timestamp editedAt = rs.getTimestamp("edited_at");
        return new SummaryMessage(
                rs.getObject("id", UUID.class),
                rs.getLong("seq"),
                rs.getObject("sender_id", UUID.class),
                rs.getString("sender_name"),
                truncate(rs.getString("content")),
                rs.getTimestamp("created_at").toInstant(),
                editedAt == null ? null : editedAt.toInstant());
    };

    private final JdbcTemplate jdbcTemplate;

    public SummaryWindowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SummaryMessage> findWindow(
            UUID conversationId,
            SummaryRequest request,
            Long afterSeq,
            Instant fromInclusive,
            Instant toExclusive) {
        StringBuilder sql = new StringBuilder(SELECT_MESSAGES);
        List<Object> parameters = new ArrayList<>();
        parameters.add(conversationId);
        if (afterSeq != null) {
            sql.append(" AND m.seq > ?");
            parameters.add(afterSeq);
        }
        if (fromInclusive != null) {
            sql.append(" AND m.created_at >= ? AND m.created_at < ?");
            parameters.add(OffsetDateTime.ofInstant(fromInclusive, ZoneOffset.UTC));
            parameters.add(OffsetDateTime.ofInstant(toExclusive, ZoneOffset.UTC));
        }
        sql.append(" ORDER BY m.seq DESC LIMIT ?");
        parameters.add(limit(request));

        List<SummaryMessage> newestFirst = jdbcTemplate.query(
                sql.toString(), MESSAGE_MAPPER, parameters.toArray());
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    private static int limit(SummaryRequest request) {
        return switch (request.scope()) {
            case LAST_N -> Math.min(request.limit(), MAX_MESSAGES);
            case UNREAD, TODAY -> MAX_MESSAGES;
        };
    }

    private static String truncate(String text) {
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= MAX_MESSAGE_CHARACTERS) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_MESSAGE_CHARACTERS));
    }
}
