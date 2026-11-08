package com.nexachat.rag;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcRagMessageIndex implements RagMessageIndex {

    private static final String MESSAGE_COLUMNS = """
            m.id, m.conversation_id, m.seq, m.sender_id, u.display_name AS sender_name, m.content,
            m.created_at, m.edited_at, m.reply_to_id
            """;

    private static final String MESSAGE_WHERE = """
            FROM messages m
            JOIN users u ON u.id = m.sender_id
            WHERE m.conversation_id = ?
              AND m.type = 'TEXT'
              AND m.deleted_at IS NULL
              AND m.content IS NOT NULL
              AND btrim(m.content) <> ''
            """;

    private static final RowMapper<RagMessage> MESSAGE_MAPPER = (rs, rowNum) -> {
        Timestamp editedAt = rs.getTimestamp("edited_at");
        return new RagMessage(
                rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getLong("seq"),
                rs.getObject("sender_id", UUID.class),
                rs.getString("sender_name"),
                rs.getString("content"),
                rs.getTimestamp("created_at").toInstant(),
                editedAt == null ? null : editedAt.toInstant(),
                rs.getObject("reply_to_id", UUID.class));
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcRagMessageIndex(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<RagMessage> recentMessages(UUID conversationId, int limit) {
        String sql = "SELECT " + MESSAGE_COLUMNS + MESSAGE_WHERE
                + " ORDER BY m.seq DESC LIMIT ?";
        List<RagMessage> newestFirst = jdbcTemplate.query(
                sql, MESSAGE_MAPPER, conversationId, Math.max(1, limit));
        List<RagMessage> ordered = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    @Override
    public List<RagMessage> lexicalCandidates(UUID conversationId, List<String> terms, int limit) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT " + MESSAGE_COLUMNS + MESSAGE_WHERE);
        List<Object> parameters = new ArrayList<>();
        parameters.add(conversationId);
        sql.append(" AND (");
        for (int index = 0; index < terms.size(); index++) {
            if (index > 0) {
                sql.append(" OR ");
            }
            sql.append("public.f_unaccent(m.content) ILIKE '%' || public.f_unaccent(?) || '%' ESCAPE chr(92)");
            parameters.add(escapeLike(terms.get(index)));
        }
        sql.append(") ORDER BY m.seq DESC LIMIT ?");
        parameters.add(Math.max(1, limit));
        return List.copyOf(jdbcTemplate.query(sql.toString(), MESSAGE_MAPPER, parameters.toArray()));
    }

    @Override
    public Map<UUID, float[]> loadVectors(String model, Collection<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT message_id, vector, dim FROM message_embeddings"
                + " WHERE model = ? AND message_id = ANY(?)";
        Map<UUID, float[]> vectors = new HashMap<>();
        jdbcTemplate.query(sql, statement -> {
            statement.setString(1, model);
            statement.setArray(2, uuidArray(statement, messageIds));
        }, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            UUID messageId = rs.getObject("message_id", UUID.class);
            int dim = rs.getInt("dim");
            vectors.put(messageId, VectorCodec.decode(rs.getBytes("vector"), dim));
        });
        return vectors;
    }

    @Override
    public Map<UUID, String> loadContentHashes(String model, Collection<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT message_id, content_hash FROM message_embeddings"
                + " WHERE model = ? AND message_id = ANY(?)";
        Map<UUID, String> hashes = new HashMap<>();
        jdbcTemplate.query(sql, statement -> {
            statement.setString(1, model);
            statement.setArray(2, uuidArray(statement, messageIds));
        }, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                hashes.put(rs.getObject("message_id", UUID.class), rs.getString("content_hash")));
        return hashes;
    }

    @Override
    public void saveVectors(String model, int dim, List<RagMessage> messages,
                            Map<UUID, float[]> vectors, Map<UUID, String> contentHashes) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO message_embeddings
                    (message_id, conversation_id, model, dim, vector, content_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (message_id) DO UPDATE SET
                    conversation_id = EXCLUDED.conversation_id,
                    model = EXCLUDED.model,
                    dim = EXCLUDED.dim,
                    vector = EXCLUDED.vector,
                    content_hash = EXCLUDED.content_hash,
                    created_at = EXCLUDED.created_at
                """;
        jdbcTemplate.batchUpdate(sql, messages, messages.size(), (statement, message) -> {
            float[] vector = vectors.get(message.id());
            String hash = contentHashes.get(message.id());
            if (vector == null || hash == null) {
                return;
            }
            statement.setObject(1, message.id());
            statement.setObject(2, message.conversationId());
            statement.setString(3, model);
            statement.setInt(4, dim);
            statement.setBytes(5, VectorCodec.encode(vector));
            statement.setString(6, hash);
        });
    }

    @Override
    public List<RagMessage> findUnindexed(String model, int limit) {
        String sql = """
                SELECT m.id, m.conversation_id, m.seq, m.sender_id,
                       u.display_name AS sender_name, m.content,
                       m.created_at, m.edited_at, m.reply_to_id
                FROM messages m
                JOIN users u ON u.id = m.sender_id
                LEFT JOIN message_embeddings e ON e.message_id = m.id AND e.model = ?
                WHERE m.type = 'TEXT'
                  AND m.deleted_at IS NULL
                  AND m.content IS NOT NULL
                  AND btrim(m.content) <> ''
                  AND e.message_id IS NULL
                ORDER BY m.seq DESC
                LIMIT ?
                """;
        return List.copyOf(jdbcTemplate.query(sql, MESSAGE_MAPPER, model, Math.max(1, limit)));
    }

    @Override
    public Map<UUID, String> loadMessageContentHashes(Collection<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT id, content, edited_at FROM messages
                WHERE deleted_at IS NULL AND content IS NOT NULL AND id = ANY(?)
                """;
        Map<UUID, String> hashes = new HashMap<>();
        jdbcTemplate.query(sql, statement -> statement.setArray(1, uuidArray(statement, messageIds)),
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        hashes.put(rs.getObject("id", UUID.class),
                                RagHashes.messageContentHash(
                                        rs.getObject("id", UUID.class),
                                        rs.getTimestamp("edited_at"),
                                        rs.getString("content"))));
        return hashes;
    }

    @Override
    public RagMessage findMessage(UUID conversationId, UUID messageId) {
        String sql = "SELECT " + MESSAGE_COLUMNS + MESSAGE_WHERE + " AND m.id = ?";
        List<RagMessage> messages = jdbcTemplate.query(
                sql, MESSAGE_MAPPER, conversationId, messageId);
        return messages.stream().findFirst().orElse(null);
    }

    private static java.sql.Array uuidArray(PreparedStatement statement, Collection<UUID> ids)
            throws SQLException {
        return statement.getConnection().createArrayOf("uuid", ids.toArray(UUID[]::new));
    }

    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
