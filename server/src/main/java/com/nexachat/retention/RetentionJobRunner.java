package com.nexachat.retention;

import com.nexachat.message.SyncProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/** Runs one bounded, skip-locked batch for each enabled retention job. */
@Component("retentionJobRunner")
@Profile("!test")
public class RetentionJobRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionJobRunner.class);

    private static final String ROWS_DELETED = "nexachat.retention.rows.deleted";
    private static final String ROWS_OBSERVED = "nexachat.retention.rows.observed";
    private static final String FILES_DELETED = "nexachat.retention.files.deleted";
    private static final String JOB_RUNS = "nexachat.retention.job.runs";
    private static final String JOB_ERRORS = "nexachat.retention.job.errors";

    private final JdbcTemplate jdbcTemplate;
    private final RetentionProperties properties;
    private final SyncProperties syncProperties;
    private final RetentionAttachmentCleaner attachmentCleaner;
    private final RetentionPollCleaner pollCleaner;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public RetentionJobRunner(JdbcTemplate jdbcTemplate,
                              RetentionProperties properties,
                              SyncProperties syncProperties,
                              RetentionAttachmentCleaner attachmentCleaner,
                              RetentionPollCleaner pollCleaner,
                              MeterRegistry meterRegistry,
                              PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.syncProperties = syncProperties;
        this.attachmentCleaner = attachmentCleaner;
        this.pollCleaner = pollCleaner;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setTimeout(properties.getTransactionTimeoutSeconds());
    }

    @Scheduled(fixedDelayString = "${nexachat.retention.interval-ms:3600000}")
    public void scheduledSweep() {
        runEnabledJobs();
    }

    /** Returns no results while the global safety switch is off. */
    public Map<Job, JobRunResult> runEnabledJobs() {
        if (!properties.isEnabled()) {
            return Map.of();
        }
        Map<Job, JobRunResult> results = new EnumMap<>(Job.class);
        for (Job job : Job.values()) {
            if (!properties.isJobEnabled(job)) {
                continue;
            }
            try {
                results.put(job, runNow(job, properties.isDryRun()));
            } catch (RuntimeException failure) {
                log.warn("Retention job {} failed ({})", job.id(), failure.getClass().getSimpleName());
            }
        }
        return Map.copyOf(results);
    }

    /** Executes one job batch; package visibility keeps it an internal operator/test seam. */
    JobRunResult runNow(Job job, boolean dryRun) {
        try {
            JobRunResult result = transactionTemplate.execute(status -> {
                jdbcTemplate.execute("SET LOCAL statement_timeout = " + properties.getStatementTimeoutMs());
                return execute(job, dryRun);
            });
            if (result == null) {
                throw new IllegalStateException("Retention transaction returned no result");
            }
            recordSuccess(job, result, dryRun);
            return result;
        } catch (RuntimeException failure) {
            meterRegistry.counter(JOB_RUNS, "job", job.id(), "outcome", "failure").increment();
            meterRegistry.counter(JOB_ERRORS, "job", job.id()).increment();
            log.warn("Retention job {} failed ({})", job.id(), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private JobRunResult execute(Job job, boolean dryRun) {
        return switch (job) {
            case JOIN_REQUESTS -> deleteBatch("group_join_requests",
                    "candidate.status IN ('APPROVED', 'REJECTED') AND candidate.reviewed_at <= ?",
                    "candidate.reviewed_at, candidate.id",
                    cutoffDays(properties.getJobs().getJoinRequests().getRetentionDays()), dryRun);
            case INVITE_BANS -> deleteBatch("group_invite_bans", """
                            candidate.invite_link_id IS NOT NULL
                            AND EXISTS (
                                SELECT 1 FROM group_invite_links l
                                WHERE l.id = candidate.invite_link_id AND (
                                    l.revoked_at <= ? OR l.expires_at <= ?
                                    OR (l.max_uses IS NOT NULL AND l.use_count >= l.max_uses AND l.updated_at <= ?)
                                )
                            )
                            """, "candidate.created_at, candidate.id",
                    repeat(cutoffDays(properties.getJobs().getInviteLinks().getGraceDays()), 3), dryRun);
            case INVITE_LINKS -> deleteBatch("group_invite_links", """
                            (
                                candidate.revoked_at <= ? OR candidate.expires_at <= ?
                                OR (candidate.max_uses IS NOT NULL AND candidate.use_count >= candidate.max_uses
                                    AND candidate.updated_at <= ?)
                            )
                            AND NOT EXISTS (
                                SELECT 1 FROM group_join_requests jr WHERE jr.invite_link_id = candidate.id
                            )
                            AND NOT EXISTS (
                                SELECT 1 FROM group_invite_bans b WHERE b.invite_link_id = candidate.id
                            )
                            """, "candidate.updated_at, candidate.id",
                    repeat(cutoffDays(properties.getJobs().getInviteLinks().getGraceDays()), 3), dryRun);
            case IDEMPOTENCY -> deleteBatch("request_idempotency",
                    "candidate.created_at <= ?", "candidate.created_at, candidate.user_id, candidate.key",
                    cutoffHours(properties.getJobs().getIdempotency().getTtlHours()), dryRun);
            case REFRESH_TOKENS -> deleteBatch("refresh_tokens",
                    "candidate.expires_at <= ? OR candidate.revoked_at <= ?",
                    "LEAST(candidate.expires_at, COALESCE(candidate.revoked_at, 'infinity'::timestamptz)), candidate.id",
                    repeat(cutoffDays(properties.getJobs().getRefreshTokens().getRetentionDays()), 2), dryRun);
            case DEVICE_TOKENS -> deleteBatch("user_device_tokens", """
                            (candidate.last_used_at IS NOT NULL AND candidate.last_used_at <= ?)
                            OR (candidate.last_used_at IS NULL AND candidate.updated_at <= ?)
                            """, "COALESCE(candidate.last_used_at, candidate.updated_at), candidate.id",
                    repeat(cutoffDays(properties.getJobs().getDeviceTokens().getStaleDays()), 2), dryRun);
            case REMINDERS -> deleteBatch("conversation_reminders",
                    "candidate.repeat_rule = 'NONE' AND candidate.fired_at IS NOT NULL AND candidate.fired_at <= ?",
                    "candidate.fired_at, candidate.id",
                    cutoffDays(properties.getJobs().getReminders().getRetentionDays()), dryRun);
            case POLLS -> pollCleaner.run(dryRun);
            case CALL_RECORDS -> deleteBatch("call_records",
                    "candidate.state = 'ENDED' AND candidate.ended_at <= ?",
                    "candidate.ended_at, candidate.call_id",
                    cutoffDays(properties.getJobs().getCallRecords().getRetentionDays()), dryRun);
            case ATTACHMENTS -> attachmentCleaner.run(dryRun);
            case MESSAGE_TOMBSTONES -> purgeMessageTombstones(dryRun);
            case REACTION_EVENTS -> deleteBatch("reaction_events",
                    "candidate.created_at <= ?", "candidate.created_at, candidate.id",
                    cutoffDays(syncProperties.getReactionRetentionDays()), dryRun);
            case SUMMARIES -> deleteBatch("message_summaries",
                    "candidate.expires_at <= ?", "candidate.expires_at, candidate.id", now(), dryRun);
            case SUMMARY_LIMITS -> deleteLimits("summary_request_limits",
                    properties.getJobs().getSummaryLimits(), dryRun);
            case RAG_CACHE -> deleteBatch("rag_ask_cache",
                    "candidate.expires_at <= ?", "candidate.expires_at, candidate.id", now(), dryRun);
            case RAG_LIMITS -> deleteLimits("rag_ask_request_limits",
                    properties.getJobs().getRagLimits(), dryRun);
        };
    }

    private JobRunResult deleteLimits(String table, RetentionProperties.Limits limits, boolean dryRun) {
        return deleteBatch(table, """
                        (candidate.window_type = 'MINUTE' AND candidate.window_start <= ?)
                        OR (candidate.window_type = 'DAY' AND candidate.window_start <= ?)
                        """, "candidate.window_start, candidate.window_type, candidate.user_id",
                cutoffDays(limits.getMinuteRetentionDays()),
                cutoffDays(limits.getDayRetentionDays()), dryRun);
    }

    private JobRunResult purgeMessageTombstones(boolean dryRun) {
        OffsetDateTime cutoff = cutoffDays(properties.getJobs().getMessageTombstones().getRetentionDays());
        String predicate = """
                candidate.deleted_at IS NOT NULL AND candidate.deleted_at <= ?
                AND (
                    candidate.content IS NOT NULL OR candidate.mention_all
                    OR EXISTS (SELECT 1 FROM message_embeddings e WHERE e.message_id = candidate.id)
                    OR EXISTS (SELECT 1 FROM message_mentions m WHERE m.message_id = candidate.id)
                )
                """;
        if (dryRun) {
            Integer candidates = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT candidate.id FROM messages candidate WHERE %s
                        ORDER BY candidate.deleted_at, candidate.id LIMIT ?
                    ) selected
                    """.formatted(predicate), Integer.class, cutoff, properties.getBatchSize());
            return new JobRunResult(candidates == null ? 0 : candidates, 0, 0);
        }

        Integer purged = jdbcTemplate.queryForObject("""
                WITH candidates AS MATERIALIZED (
                    SELECT candidate.id
                    FROM messages candidate
                    WHERE %s
                    ORDER BY candidate.deleted_at, candidate.id
                    LIMIT ?
                    FOR UPDATE OF candidate SKIP LOCKED
                ), cleared AS (
                    UPDATE messages m SET content = NULL, mention_all = FALSE
                    FROM candidates c
                    WHERE m.id = c.id AND (m.content IS NOT NULL OR m.mention_all)
                    RETURNING 1
                ), embeddings AS (
                    DELETE FROM message_embeddings e USING candidates c
                    WHERE e.message_id = c.id
                    RETURNING 1
                ), mentions AS (
                    DELETE FROM message_mentions mm USING candidates c
                    WHERE mm.message_id = c.id
                    RETURNING 1
                )
                SELECT (SELECT count(*) FROM cleared)
                     + (SELECT count(*) FROM embeddings)
                     + (SELECT count(*) FROM mentions)
                """.formatted(predicate), Integer.class, cutoff, properties.getBatchSize());
        return new JobRunResult(purged == null ? 0 : purged, 0, 0);
    }

    private JobRunResult deleteBatch(String table,
                                    String predicate,
                                    String orderBy,
                                    Object[] parameters,
                                    boolean dryRun) {
        int batchSize = properties.getBatchSize();
        if (dryRun) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT 1 FROM %s candidate
                        WHERE %s
                        ORDER BY %s
                        LIMIT ?
                    ) selected
                    """.formatted(table, predicate, orderBy), Integer.class,
                    append(parameters, batchSize));
            return new JobRunResult(count == null ? 0 : count, 0, 0);
        }

        Integer count = jdbcTemplate.queryForObject("""
                WITH candidates AS MATERIALIZED (
                    SELECT candidate.ctid AS row_tid
                    FROM %s candidate
                    WHERE %s
                    ORDER BY %s
                    LIMIT ?
                    FOR UPDATE OF candidate SKIP LOCKED
                ), deleted AS (
                    DELETE FROM %s target USING candidates c
                    WHERE target.ctid = c.row_tid
                    RETURNING 1
                )
                SELECT count(*) FROM deleted
                """.formatted(table, predicate, orderBy, table), Integer.class,
                append(parameters, batchSize));
        return new JobRunResult(count == null ? 0 : count, 0, 0);
    }

    private JobRunResult deleteBatch(String table,
                                    String predicate,
                                    String orderBy,
                                    Object parameter,
                                    boolean dryRun) {
        return deleteBatch(table, predicate, orderBy, new Object[] {parameter}, dryRun);
    }

    private JobRunResult deleteBatch(String table,
                                    String predicate,
                                    String orderBy,
                                    Object first,
                                    Object second,
                                    boolean dryRun) {
        return deleteBatch(table, predicate, orderBy, new Object[] {first, second}, dryRun);
    }

    private JobRunResult deleteBatch(String table,
                                    String predicate,
                                    String orderBy,
                                    Object first,
                                    Object second,
                                    Object third,
                                    boolean dryRun) {
        return deleteBatch(table, predicate, orderBy, new Object[] {first, second, third}, dryRun);
    }

    private void recordSuccess(Job job, JobRunResult result, boolean dryRun) {
        String outcome = dryRun ? "dry_run" : result.errors() > 0 ? "partial" : "success";
        meterRegistry.counter(JOB_RUNS, "job", job.id(), "outcome", outcome).increment();
        if (dryRun) {
            increment(ROWS_OBSERVED, job, result.rowsAffected());
        } else {
            increment(ROWS_DELETED, job, result.rowsAffected());
            increment(FILES_DELETED, job, result.filesDeleted());
        }
        increment(JOB_ERRORS, job, result.errors());
    }

    private void increment(String metric, Job job, int amount) {
        if (amount > 0) {
            meterRegistry.counter(metric, "job", job.id()).increment(amount);
        }
    }

    private OffsetDateTime cutoffDays(int days) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusDays(days);
    }

    private OffsetDateTime cutoffHours(int hours) {
        return OffsetDateTime.now(ZoneOffset.UTC).minus(Duration.ofHours(hours));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static Object[] repeat(Object value, int times) {
        Object[] result = new Object[times];
        Arrays.fill(result, value);
        return result;
    }

    private static Object[] append(Object[] values, Object last) {
        Object[] result = Arrays.copyOf(values, values.length + 1);
        result[values.length] = last;
        return result;
    }

    public enum Job {
        JOIN_REQUESTS("join-requests"),
        INVITE_BANS("invite-bans"),
        INVITE_LINKS("invite-links"),
        IDEMPOTENCY("idempotency"),
        REFRESH_TOKENS("refresh-tokens"),
        DEVICE_TOKENS("device-tokens"),
        REMINDERS("reminders"),
        POLLS("polls"),
        CALL_RECORDS("call-records"),
        ATTACHMENTS("attachments"),
        MESSAGE_TOMBSTONES("message-tombstones"),
        REACTION_EVENTS("reaction-events"),
        SUMMARIES("summaries"),
        SUMMARY_LIMITS("summary-limits"),
        RAG_CACHE("rag-cache"),
        RAG_LIMITS("rag-limits");

        private final String id;

        Job(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record JobRunResult(int rowsAffected, int filesDeleted, int errors) {
    }
}
