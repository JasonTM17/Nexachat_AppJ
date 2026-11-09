package com.nexachat.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.SummaryLanguage;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Exact and semantic question cache. A cached answer is replayed only when the
 * cited messages still exist, are not deleted and their content hash matches
 * the hash recorded when the answer was cached.
 */
@Repository
@Profile("!test")
public class RagQuestionCache {

    private static final int SEMANTIC_SCAN_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RagMessageIndex index;
    private final Clock clock;
    private final RagProperties properties;

    public RagQuestionCache(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RagMessageIndex index,
            Clock clock,
            RagProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.index = index;
        this.clock = clock;
        this.properties = properties;
    }

    public Optional<CachedAsk> findExact(UUID conversationId, String model, String questionHash) {
        List<CachedAsk> entries = jdbcTemplate.query(
                """
                SELECT payload_json::text AS payload_json, cited_message_ids, cited_content_hash,
                       language, created_at, expires_at
                FROM rag_ask_cache
                WHERE conversation_id = ? AND model = ? AND question_hash = ? AND expires_at > ?
                """,
                statement -> {
                    statement.setObject(1, conversationId);
                    statement.setString(2, model);
                    statement.setString(3, questionHash);
                    statement.setObject(4, now());
                },
                mapper());
        return entries.stream().findFirst();
    }

    public List<CachedQuestion> findCandidates(UUID conversationId, String model) {
        return jdbcTemplate.query(
                """
                SELECT payload_json::text AS payload_json, cited_message_ids, cited_content_hash,
                       language, question_vector, question_hash, created_at, expires_at
                FROM rag_ask_cache
                WHERE conversation_id = ? AND model = ? AND expires_at > ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
                statement -> {
                    statement.setObject(1, conversationId);
                    statement.setString(2, model);
                    statement.setObject(3, now());
                    statement.setInt(4, SEMANTIC_SCAN_LIMIT);
                },
                (rs, rowNum) -> {
                    CachedAsk ask = mapAsk(rs);
                    float[] vector = VectorCodec.decode(rs.getBytes("question_vector"),
                            rs.getBytes("question_vector").length / Float.BYTES);
                    return new CachedQuestion(ask, vector, rs.getString("question_hash"));
                });
    }

    public void save(
            UUID conversationId,
            String model,
            String questionHash,
            float[] questionVector,
            CachedAsk ask) {
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plusSeconds(
                Math.max(1, properties.getAsk().getCacheTtlHours()) * 3600L);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new CachedAskPayload(
                    ask.answer(), ask.citations(), ask.mode()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("ask cache payload is invalid", exception);
        }
        jdbcTemplate.update(
                """
                INSERT INTO rag_ask_cache
                    (conversation_id, model, question_hash, question_vector, language,
                     payload_json, cited_message_ids, cited_content_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (conversation_id, model, question_hash) DO UPDATE SET
                    question_vector = EXCLUDED.question_vector,
                    language = EXCLUDED.language,
                    payload_json = EXCLUDED.payload_json,
                    cited_message_ids = EXCLUDED.cited_message_ids,
                    cited_content_hash = EXCLUDED.cited_content_hash,
                    created_at = EXCLUDED.created_at,
                    expires_at = EXCLUDED.expires_at
                """,
                statement -> {
                    statement.setObject(1, conversationId);
                    statement.setString(2, model);
                    statement.setString(3, questionHash);
                    statement.setBytes(4, VectorCodec.encode(questionVector));
                    statement.setString(5, ask.language().name());
                    statement.setString(6, payload);
                    statement.setArray(7, statement.getConnection().createArrayOf(
                            "uuid", ask.citedIds().toArray(UUID[]::new)));
                    statement.setString(8, ask.citedHash());
                    statement.setObject(9, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
                    statement.setObject(10, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
                });
    }

    /**
     * Drops every cached ask answer for the conversation. Recall and opt-out
     * both invalidate the full conversation cache because rows record only
     * citations, not the full prompt message set.
     */
    public void deleteAllForConversation(UUID conversationId) {
        jdbcTemplate.update("DELETE FROM rag_ask_cache WHERE conversation_id = ?", conversationId);
    }

    public boolean citationsValid(List<UUID> citedIds, String citedHash) {
        if (citedIds == null || citedIds.isEmpty() || citedHash == null) {
            return false;
        }
        Map<UUID, String> current = index.loadMessageContentHashes(citedIds);
        if (current.size() != citedIds.size()) {
            return false;
        }
        return citedHash.equals(RagHashes.combineHashes(current));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private RowMapper<CachedAsk> mapper() {
        return (rs, rowNum) -> mapAsk(rs);
    }

    private CachedAsk mapAsk(java.sql.ResultSet rs) throws java.sql.SQLException {
        String payloadJson = rs.getString("payload_json");
        CachedAskPayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, CachedAskPayload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("ask cache payload is invalid", exception);
        }
        UUID[] citedIds = new UUID[0];
        Array array = rs.getArray("cited_message_ids");
        if (array != null) {
            Object values = array.getArray();
            if (values instanceof UUID[] uuids) {
                citedIds = uuids;
            }
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new CachedAsk(
                payload.answer(),
                payload.citations(),
                payload.mode(),
                List.of(citedIds),
                rs.getString("cited_content_hash"),
                SummaryLanguage.valueOf(rs.getString("language")));
    }

    public record CachedAsk(
            String answer,
            List<RagCitation> citations,
            String mode,
            List<UUID> citedIds,
            String citedHash,
            SummaryLanguage language) {

        public CachedAsk {
            citations = citations == null ? List.of() : List.copyOf(citations);
            citedIds = citedIds == null ? List.of() : List.copyOf(citedIds);
        }
    }

    public record CachedQuestion(CachedAsk ask, float[] vector, String questionHash) {
    }

    public record CachedAskPayload(String answer, List<RagCitation> citations, String mode) {
    }
}
