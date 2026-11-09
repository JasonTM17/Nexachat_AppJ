package com.nexachat.retention;

import com.nexachat.attachment.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Removes only expired attachment rows whose server-owned files are unreferenced. */
@Component
@Profile("!test")
class RetentionAttachmentCleaner {

    private static final Logger log = LoggerFactory.getLogger(RetentionAttachmentCleaner.class);

    private static final String REFERENCE_GUARD = """
            AND NOT EXISTS (
                SELECT 1 FROM message_attachments other
                WHERE other.storage_key = a.storage_key AND other.id <> a.id
            )
            """
            + avatarReferenceGuard("users", "u")
            + avatarReferenceGuard("conversations", "c");

    private static final String CANDIDATE_PREDICATE = """
            a.storage_key ~ '^attachments/[0-9a-fA-F-]{36}[.][a-z0-9]+$'
            AND (
                (a.message_id IS NULL AND a.created_at <= ?)
                OR (m.deleted_at IS NOT NULL AND m.deleted_at <= ?)
            )
            """ + REFERENCE_GUARD;

    private static String avatarReferenceGuard(String table, String alias) {
        String avatarPath = "split_part(split_part(" + alias + ".avatar_url, '#', 1), '?', 1)";
        String expectedPath = "'/api/v1/files/' || a.storage_key";
        return """
            AND NOT EXISTS (
                SELECT 1 FROM __TABLE__ __ALIAS__
                WHERE __AVATAR_PATH__ ~* (
                    SELECT string_agg(
                        '(?:' || CASE
                            WHEN substr(__EXPECTED_PATH__, positions.pos, 1) = '.' THEN '[.]'
                            ELSE substr(__EXPECTED_PATH__, positions.pos, 1)
                        END || '|%' || lpad(to_hex(ascii(substr(__EXPECTED_PATH__, positions.pos, 1))), 2, '0') || ')',
                        '' ORDER BY positions.pos
                    ) || '$'
                    FROM generate_series(1, char_length(__EXPECTED_PATH__)) AS positions(pos)
                )
            )
            """.replace("__TABLE__", table)
                .replace("__ALIAS__", alias)
                .replace("__AVATAR_PATH__", avatarPath)
                .replace("__EXPECTED_PATH__", expectedPath);
    }

    private static final String SELECT_CANDIDATES = """
            SELECT a.id, a.storage_key
            FROM message_attachments a
            LEFT JOIN messages m ON m.id = a.message_id
            WHERE %s
            ORDER BY a.created_at, a.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;
    private final RetentionProperties properties;

    RetentionAttachmentCleaner(JdbcTemplate jdbcTemplate,
                               StorageService storageService,
                               RetentionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageService = storageService;
        this.properties = properties;
    }

    RetentionJobRunner.JobRunResult run(boolean dryRun) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime unboundCutoff = now.minusHours(properties.getJobs().getAttachments().getUnboundHours());
        OffsetDateTime tombstoneCutoff = now.minusDays(
                properties.getJobs().getAttachments().getDeletedMessageDays());
        int batchSize = properties.getBatchSize();

        if (dryRun) {
            String countSql = "SELECT count(*) FROM (" + SELECT_CANDIDATES.formatted(CANDIDATE_PREDICATE)
                    + ") candidates";
            Integer count = jdbcTemplate.queryForObject(countSql, Integer.class,
                    unboundCutoff, tombstoneCutoff, batchSize);
            return new RetentionJobRunner.JobRunResult(count == null ? 0 : count, 0, 0);
        }

        String sql = """
                WITH candidates AS MATERIALIZED (
                    SELECT a.ctid, a.id, a.storage_key
                    FROM message_attachments a
                    LEFT JOIN messages m ON m.id = a.message_id
                    WHERE %s
                    ORDER BY a.created_at, a.id
                    LIMIT ?
                    FOR UPDATE OF a SKIP LOCKED
                )
                SELECT id, storage_key FROM candidates
                """.formatted(CANDIDATE_PREDICATE);
        List<FileRow> candidates = jdbcTemplate.query(sql,
                (rs, rowNum) -> new FileRow(rs.getObject("id", UUID.class), rs.getString("storage_key")),
                unboundCutoff, tombstoneCutoff, batchSize);

        int rows = 0;
        int files = 0;
        int errors = 0;
        for (FileRow row : candidates) {
            try {
                boolean fileDeleted = storageService.deleteIfExists(row.storageKey());
                int rowDeleted = jdbcTemplate.update("""
                        DELETE FROM message_attachments a
                        WHERE a.id = ? AND a.storage_key = ?
                        """ + REFERENCE_GUARD, row.id(), row.storageKey());
                if (rowDeleted == 1) {
                    rows++;
                    if (fileDeleted) {
                        files++;
                    }
                }
            } catch (RuntimeException failure) {
                errors++;
                log.warn("Retention attachment cleanup skipped one file ({})",
                        failure.getClass().getSimpleName());
            }
        }
        return new RetentionJobRunner.JobRunResult(rows, files, errors);
    }

    private record FileRow(UUID id, String storageKey) {
    }
}
