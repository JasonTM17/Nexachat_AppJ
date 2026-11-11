package com.nexachat.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class SummaryCacheRepository {

    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final String SELECT_VALID = """
            SELECT id, from_seq, to_seq, content_hash, provider, language,
                   payload_json::text AS payload_json, created_at, expires_at
            FROM message_summaries
            WHERE conversation_id = ? AND from_seq = ? AND to_seq = ?
              AND content_hash = ? AND provider = ? AND language = ?
              AND expires_at > ?
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO message_summaries
                (id, conversation_id, from_seq, to_seq, content_hash, provider, language,
                 payload_json, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (conversation_id, from_seq, to_seq, content_hash, provider, language)
            DO UPDATE SET id = EXCLUDED.id,
                          payload_json = EXCLUDED.payload_json,
                          created_at = EXCLUDED.created_at,
                          expires_at = EXCLUDED.expires_at
            WHERE message_summaries.expires_at <= EXCLUDED.created_at
            RETURNING id, from_seq, to_seq, content_hash, provider, language,
                      payload_json::text AS payload_json, created_at, expires_at
            """;

    private static final String REPLACE_SQL = """
            INSERT INTO message_summaries
                (id, conversation_id, from_seq, to_seq, content_hash, provider, language,
                 payload_json, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (conversation_id, from_seq, to_seq, content_hash, provider, language)
            DO UPDATE SET id = EXCLUDED.id,
                          payload_json = EXCLUDED.payload_json,
                          created_at = EXCLUDED.created_at,
                          expires_at = EXCLUDED.expires_at
            RETURNING id, from_seq, to_seq, content_hash, provider, language,
                      payload_json::text AS payload_json, created_at, expires_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SummaryCacheRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<SummaryCacheEntry> findValid(
            UUID conversationId,
            long fromSeq,
            long toSeq,
            String contentHash,
            String provider,
            SummaryLanguage language) {
        return queryValid(conversationId, fromSeq, toSeq, contentHash, provider, language, clock.instant());
    }

    /**
     * Removes a cache row that can no longer be replayed because a cited
     * message belongs to a member who opted out after the row was written.
     */
    @Transactional
    public void delete(
            UUID conversationId,
            long fromSeq,
            long toSeq,
            String contentHash,
            String provider,
            SummaryLanguage language) {
        jdbcTemplate.update("""
                DELETE FROM message_summaries
                WHERE conversation_id = ? AND from_seq = ? AND to_seq = ?
                  AND content_hash = ? AND provider = ? AND language = ?
                """, conversationId, fromSeq, toSeq, contentHash, provider, language.name());
    }

    @Transactional
    public void deleteContainingSequence(UUID conversationId, long sequence) {
        jdbcTemplate.update("""
                DELETE FROM message_summaries
                WHERE conversation_id = ? AND from_seq <= ? AND to_seq >= ?
                """, conversationId, sequence, sequence);
    }

    @Transactional
    public void deleteContainingMessage(UUID conversationId, UUID messageId) {
        jdbcTemplate.update("""
                DELETE FROM message_summaries AS cached_summary
                USING messages AS source_message
                WHERE cached_summary.conversation_id = ?
                  AND source_message.id = ?
                  AND source_message.conversation_id = cached_summary.conversation_id
                  AND source_message.seq BETWEEN cached_summary.from_seq AND cached_summary.to_seq
                """, conversationId, messageId);
    }

    @Transactional
    public SavedSummary saveIfAbsent(
            UUID requestingUserId,
            UUID conversationId,
            SummaryWindow window,
            String contentHash,
            String provider,
            SummaryLanguage requestedLanguage,
            SummaryGeneration generation) {
        lockActiveRequester(requestingUserId, conversationId);
        Optional<SummaryCacheEntry> inserted = write(
                UPSERT_SQL, conversationId, window, contentHash, provider, requestedLanguage, generation);
        if (inserted.isPresent()) {
            return new SavedSummary(inserted.get(), true);
        }

        SummaryCacheEntry winner = queryValid(
                conversationId, window.fromSeq(), window.toSeq(), contentHash,
                provider, requestedLanguage, clock.instant())
                .orElseThrow(() -> new IllegalStateException("Concurrent summary cache write was not visible"));
        return new SavedSummary(winner, false);
    }

    @Transactional
    public SummaryCacheEntry replace(
            UUID requestingUserId,
            UUID conversationId,
            SummaryWindow window,
            String contentHash,
            String provider,
            SummaryLanguage requestedLanguage,
            SummaryGeneration generation) {
        lockActiveRequester(requestingUserId, conversationId);
        return write(REPLACE_SQL, conversationId, window, contentHash, provider, requestedLanguage, generation)
                .orElseThrow(() -> new IllegalStateException("Summary cache refresh did not return a row"));
    }

    private void lockActiveRequester(UUID requestingUserId, UUID conversationId) {
        List<Boolean> activeMembers = jdbcTemplate.query("""
                SELECT requester.deleted_at IS NULL
                FROM users requester
                JOIN conversation_members member ON member.user_id = requester.id
                WHERE requester.id = ? AND member.conversation_id = ?
                FOR SHARE OF requester
                """, (rs, rowNum) -> rs.getBoolean(1), requestingUserId, conversationId);
        if (activeMembers.isEmpty() || !activeMembers.getFirst()) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
    }

    private Optional<SummaryCacheEntry> write(
            String sql,
            UUID conversationId,
            SummaryWindow window,
            String contentHash,
            String provider,
            SummaryLanguage requestedLanguage,
            SummaryGeneration generation) {
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(CACHE_TTL);
        String json;
        try {
            json = objectMapper.writeValueAsString(SummaryCachePayload.from(generation));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize validated summary", ex);
        }

        List<SummaryCacheEntry> written = jdbcTemplate.query(
                sql,
                statement -> {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, conversationId);
                    statement.setLong(3, window.fromSeq());
                    statement.setLong(4, window.toSeq());
                    statement.setString(5, contentHash);
                    statement.setString(6, provider);
                    statement.setString(7, requestedLanguage.name());
                    statement.setString(8, json);
                    statement.setObject(9, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
                    statement.setObject(10, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
                },
                entryMapper());
        return written.stream().findFirst();
    }

    private Optional<SummaryCacheEntry> queryValid(
            UUID conversationId,
            long fromSeq,
            long toSeq,
            String contentHash,
            String provider,
            SummaryLanguage language,
            Instant now) {
        List<SummaryCacheEntry> entries = jdbcTemplate.query(
                SELECT_VALID,
                statement -> {
                    statement.setObject(1, conversationId);
                    statement.setLong(2, fromSeq);
                    statement.setLong(3, toSeq);
                    statement.setString(4, contentHash);
                    statement.setString(5, provider);
                    statement.setString(6, language.name());
                    statement.setObject(7, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
                },
                entryMapper());
        return entries.stream().findFirst();
    }

    private SummaryCachePayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, SummaryCachePayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Summary cache payload is invalid", ex);
        }
    }

    private RowMapper<SummaryCacheEntry> entryMapper() {
        return (rs, rowNum) -> new SummaryCacheEntry(
                rs.getObject("id", UUID.class),
                rs.getLong("from_seq"),
                rs.getLong("to_seq"),
                rs.getString("content_hash").trim(),
                rs.getString("provider"),
                SummaryLanguage.valueOf(rs.getString("language")),
                readPayload(rs.getString("payload_json")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
    }

    public record SavedSummary(SummaryCacheEntry entry, boolean inserted) {
    }
}
