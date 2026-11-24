package com.nexachat.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The save race window of {@link SummaryCacheRepository#saveIfAbsent}: the
 * concurrent upsert loses against a live winner whose TTL then lapses before
 * the loser's re-query — the loser must install its own fresh row instead of
 * turning the race into a 500.
 */
@ExtendWith(MockitoExtension.class)
class SummaryCacheRepositoryTest {

    /** Distinct marker of the guarded upsert's conflict clause. */
    private static final String UPSERT_GUARD =
            "WHERE message_summaries.expires_at <= EXCLUDED.created_at";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-11T12:00:00Z");

    private SummaryCacheRepository repository;
    private SummaryWindow window;
    private SummaryGeneration generation;
    private SummaryCacheEntry freshEntry;

    @BeforeEach
    void setUp() {
        repository = new SummaryCacheRepository(jdbcTemplate, new ObjectMapper(),
                Clock.fixed(now, ZoneOffset.UTC));
        generation = new SummaryGeneration(new SummaryContent(
                "fresh local output", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                SummaryLanguage.EN, "LOCAL", null, null);
        UUID senderId = UUID.randomUUID();
        window = new SummaryWindow(conversationId, 0, 0, List.of(
                new SummaryMessage(UUID.randomUUID(), 1, senderId, "Sender", "one", Instant.EPOCH, null),
                new SummaryMessage(UUID.randomUUID(), 2, senderId, "Sender", "two", Instant.EPOCH, null)));
        freshEntry = new SummaryCacheEntry(UUID.randomUUID(), window.fromSeq(), window.toSeq(),
                "hash-1", "LOCAL", SummaryLanguage.EN, SummaryCachePayload.from(generation),
                now, now.plusSeconds(86400));
    }

    @Test
    void loserWhoseWinnerExpiredMidWindowInstallsItsOwnRowInsteadOfThrowing() {
        stubActiveRequester();
        // The guarded upsert loses: a live winner row already exists.
        when(jdbcTemplate.query(argThat(containsSql(UPSERT_GUARD)),
                any(PreparedStatementSetter.class), ArgumentMatchers.<RowMapper<SummaryCacheEntry>>any()))
                .thenReturn(List.of());
        // The winner's TTL lapses inside the loser's race window, so the
        // validity re-query finds nothing left to reuse.
        when(jdbcTemplate.query(argThat(containsSql("AND expires_at > ?")),
                any(PreparedStatementSetter.class), ArgumentMatchers.<RowMapper<SummaryCacheEntry>>any()))
                .thenReturn(List.of());
        // The fallback replaces the expired winner with this request's row.
        when(jdbcTemplate.query(argThat(replaceSql()),
                any(PreparedStatementSetter.class), ArgumentMatchers.<RowMapper<SummaryCacheEntry>>any()))
                .thenReturn(List.of(freshEntry));

        SummaryCacheRepository.SavedSummary saved =
                repository.saveIfAbsent(userId, conversationId, window, "hash-1", "LOCAL",
                        SummaryLanguage.EN, generation);

        assertThat(saved.entry()).isEqualTo(freshEntry);
        assertThat(saved.inserted()).as("an expired winner leaves only this request's row")
                .isTrue();
        verify(jdbcTemplate).query(argThat(replaceSql()),
                any(PreparedStatementSetter.class), ArgumentMatchers.<RowMapper<SummaryCacheEntry>>any());
    }

    @Test
    void loserWithAStillVisibleWinnerReusesItWithoutReplacingIt() {
        SummaryCacheEntry winner = new SummaryCacheEntry(UUID.randomUUID(), window.fromSeq(),
                window.toSeq(), "hash-1", "LOCAL", SummaryLanguage.EN,
                SummaryCachePayload.from(generation), now.minusSeconds(60), now.plusSeconds(86340));
        stubActiveRequester();
        when(jdbcTemplate.query(argThat(containsSql(UPSERT_GUARD)),
                any(PreparedStatementSetter.class), ArgumentMatchers.<RowMapper<SummaryCacheEntry>>any()))
                .thenReturn(List.of());
        when(jdbcTemplate.query(argThat(containsSql("AND expires_at > ?")),
                any(PreparedStatementSetter.class), ArgumentMatchers.<RowMapper<SummaryCacheEntry>>any()))
                .thenReturn(List.of(winner));

        SummaryCacheRepository.SavedSummary saved =
                repository.saveIfAbsent(userId, conversationId, window, "hash-1", "LOCAL",
                        SummaryLanguage.EN, generation);

        assertThat(saved.entry()).isEqualTo(winner);
        assertThat(saved.inserted()).isFalse();
        verify(jdbcTemplate, never()).query(argThat(replaceSql()),
                any(PreparedStatementSetter.class), ArgumentMatchers.<RowMapper<SummaryCacheEntry>>any());
    }

    private void stubActiveRequester() {
        when(jdbcTemplate.query(argThat(containsSql("FOR SHARE OF requester")),
                ArgumentMatchers.<RowMapper<Boolean>>any(), any(UUID.class), any(UUID.class)))
                .thenReturn(List.of(true));
    }

    /** Null-safe SQL probe: matcher placeholders are null during stubbing. */
    private static org.mockito.ArgumentMatcher<String> containsSql(String fragment) {
        return sql -> sql != null && sql.contains(fragment);
    }

    private static org.mockito.ArgumentMatcher<String> replaceSql() {
        return sql -> sql != null && sql.contains("DO UPDATE") && !sql.contains(UPSERT_GUARD);
    }
}
