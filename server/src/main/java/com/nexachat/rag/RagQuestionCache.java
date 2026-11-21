package com.nexachat.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.SummaryLanguage;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    private static final String CONVERSATION_LOCK_PREFIX = "rag-question-cache:";

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
                SELECT a.payload_json::text AS payload_json, a.cited_message_ids, a.cited_content_hash,
                       a.language, a.created_at, a.expires_at, a.context_version
                FROM rag_ask_cache a JOIN conversations c ON c.id = a.conversation_id
                WHERE a.conversation_id = ? AND a.model = ? AND a.question_hash = ? AND a.expires_at > ?
                  AND a.context_version = c.ai_context_version
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
                SELECT a.payload_json::text AS payload_json, a.cited_message_ids, a.cited_content_hash,
                       a.language, a.question_vector, a.question_hash, a.created_at, a.expires_at, a.context_version
                FROM rag_ask_cache a JOIN conversations c ON c.id = a.conversation_id
                WHERE a.conversation_id = ? AND a.model = ? AND a.expires_at > ?
                  AND a.context_version = c.ai_context_version
                ORDER BY a.created_at DESC
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

    public long contentVersion(UUID conversationId) {
        return contextSnapshot(conversationId).revision();
    }

    /** One SQL snapshot binds mutations and time-based expiry before any provider work. */
    public ContextSnapshot contextSnapshot(UUID conversationId) {
        List<ContextSnapshot> snapshots = jdbcTemplate.query("""
                SELECT c.ai_context_version, (
                    SELECT MIN(m.expires_at) FROM messages m
                    WHERE m.conversation_id = c.id AND m.type = 'TEXT'
                      AND m.deleted_at IS NULL AND m.encryption IS NULL
                      AND m.content IS NOT NULL AND btrim(m.content) <> ''
                      AND m.expires_at > now()
                      AND NOT EXISTS (SELECT 1 FROM conversation_ai_opt_outs o
                          WHERE o.conversation_id = m.conversation_id AND o.user_id = m.sender_id)
                ) AS context_expiry
                FROM conversations c WHERE c.id = ?
                """, (rs, rowNum) -> {
            Timestamp expiry = rs.getTimestamp("context_expiry");
            return new ContextSnapshot(rs.getLong("ai_context_version"),
                    expiry == null ? null : expiry.toInstant());
        }, conversationId);
        return snapshots.stream().findFirst().orElse(new ContextSnapshot(-1, null));
    }

    public boolean contextUnchanged(UUID conversationId, ContextSnapshot expected) {
        return expected != null && expected.revision() >= 0
                && (expected.expiresAt() == null || clock.instant().isBefore(expected.expiresAt()))
                && expected.equals(contextSnapshot(conversationId));
    }

    /** The caller holds a transaction; source mutations conflict with this parent lock. */
    public boolean lockContextForResponse(UUID conversationId, ContextSnapshot expected) {
        List<Long> versions = jdbcTemplate.query(
                "SELECT ai_context_version FROM conversations WHERE id = ? FOR SHARE",
                (rs, rowNum) -> rs.getLong(1), conversationId);
        return expected != null && !versions.isEmpty() && versions.getFirst() == expected.revision()
                && contextUnchanged(conversationId, expected);
    }

    @Transactional
    public boolean saveIfConsentSnapshotMatches(
            UUID userId,
            SummaryConsentRepository.ConsentSnapshot expectedConsent,
            UUID conversationId,
            String model,
            String questionHash,
            float[] questionVector,
            CachedAsk ask) {
        if (!expectedConsent.consented() || ask.expectedContentVersion() < 0) {
            return false;
        }
        List<SummaryConsentRepository.ConsentSnapshot> consentSnapshots = jdbcTemplate.query(
                """
                SELECT ai_summary_consent, ai_summary_consent_epoch
                FROM users
                WHERE id = ?
                FOR SHARE
                """,
                (rs, rowNum) -> new SummaryConsentRepository.ConsentSnapshot(
                        rs.getBoolean("ai_summary_consent"), rs.getLong("ai_summary_consent_epoch")),
                userId);
        if (consentSnapshots.isEmpty() || !expectedConsent.equals(consentSnapshots.getFirst())) {
            return false;
        }
        // Source transactions hold the parent row before their cache-invalidation advisory lock.
        // Take the locks in that same order so a cache fill cannot deadlock with an edit.
        List<Long> versions = jdbcTemplate.query(
                "SELECT ai_context_version FROM conversations WHERE id = ? FOR SHARE",
                (rs, rowNum) -> rs.getLong(1), conversationId);
        if (versions.isEmpty() || versions.getFirst() != ask.expectedContentVersion()) {
            return false;
        }
        lockConversation(conversationId);
        if (!contextUnchanged(conversationId,
                new ContextSnapshot(ask.expectedContentVersion(), ask.expectedContextExpiry()))
                || !citationsValid(ask.citedIds(), ask.citedHash())) {
            return false;
        }

        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plusSeconds(
                Math.max(1, properties.getAsk().getCacheTtlHours()) * 3600L);
        if (ask.expectedContextExpiry() != null && ask.expectedContextExpiry().isBefore(expiresAt)) {
            expiresAt = ask.expectedContextExpiry();
        }
        Instant cacheExpiry = expiresAt;
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
                     payload_json, cited_message_ids, cited_content_hash, created_at, expires_at, context_version)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT (conversation_id, model, question_hash) DO UPDATE SET
                    question_vector = EXCLUDED.question_vector,
                    language = EXCLUDED.language,
                    payload_json = EXCLUDED.payload_json,
                    cited_message_ids = EXCLUDED.cited_message_ids,
                    cited_content_hash = EXCLUDED.cited_content_hash,
                    created_at = EXCLUDED.created_at,
                    expires_at = EXCLUDED.expires_at,
                    context_version = EXCLUDED.context_version
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
                    statement.setObject(10, OffsetDateTime.ofInstant(cacheExpiry, ZoneOffset.UTC));
                    statement.setLong(11, ask.expectedContentVersion());
                });
        return true;
    }

    /**
     * Drops every cached ask answer for the conversation. Recall and opt-out
     * both invalidate the full conversation cache because rows record only
     * citations, not the full prompt message set.
     */
    @Transactional
    public void deleteAllForConversation(UUID conversationId) {
        lockConversation(conversationId);
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

    private void lockConversation(UUID conversationId) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet -> { },
                CONVERSATION_LOCK_PREFIX + conversationId);
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
        return new CachedAsk(
                payload.answer(),
                payload.citations(),
                payload.mode(),
                List.of(citedIds),
                rs.getString("cited_content_hash"),
                SummaryLanguage.valueOf(rs.getString("language")),
                rs.getLong("context_version"),
                null);
    }

    public record CachedAsk(
            String answer,
            List<RagCitation> citations,
            String mode,
            List<UUID> citedIds,
            String citedHash,
            SummaryLanguage language,
            long expectedContentVersion,
            Instant expectedContextExpiry) {

        public CachedAsk(String answer, List<RagCitation> citations, String mode, List<UUID> citedIds,
                         String citedHash, SummaryLanguage language, long expectedContentVersion) {
            this(answer, citations, mode, citedIds, citedHash, language, expectedContentVersion, null);
        }

        public CachedAsk(
                String answer,
                List<RagCitation> citations,
                String mode,
                List<UUID> citedIds,
                String citedHash,
                SummaryLanguage language) {
            this(answer, citations, mode, citedIds, citedHash, language, -1);
        }

        public CachedAsk {
            citations = citations == null ? List.of() : List.copyOf(citations);
            citedIds = citedIds == null ? List.of() : List.copyOf(citedIds);
        }
    }

    public record CachedQuestion(CachedAsk ask, float[] vector, String questionHash) {
    }

    public record ContextSnapshot(long revision, Instant expiresAt) {
    }

    public record CachedAskPayload(String answer, List<RagCitation> citations, String mode) {
    }
}
