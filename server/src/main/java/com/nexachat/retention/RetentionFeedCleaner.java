package com.nexachat.retention;

import com.nexachat.attachment.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Purges public feed rows that are already invisible — soft-deleted by their
 * author or hidden by staff — once the retention window has passed, and sweeps
 * uploads older than the unbound window that no post references: never-bound
 * uploads plus bound ones left behind by a crash between the post delete and
 * the image delete.
 *
 * <p>Deleting a post cascades to its comments and reactions. The post's image
 * row is removed only when no other post references it. Stored objects are
 * deleted before their rows, and a row whose object delete failed is kept so
 * the next run retries; an object that is already missing still lets the row
 * go, but only an object the storage actually removed counts as a deleted
 * file. Each image is handled at most once per run, so one failed object
 * delete reports exactly one error.</p>
 */
@Component
@Profile("!test")
class RetentionFeedCleaner {

    private static final Logger log = LoggerFactory.getLogger(RetentionFeedCleaner.class);

    private static final String POST_PREDICATE = """
            (p.deleted_at IS NOT NULL AND p.deleted_at <= ?)
            OR (p.hidden_at IS NOT NULL AND p.hidden_at <= ?)
            """;

    private static final String COMMENT_PREDICATE = """
            (c.deleted_at IS NOT NULL AND c.deleted_at <= ?)
            OR (c.hidden_at IS NOT NULL AND c.hidden_at <= ?)
            """;

    private static final String SWEEP_IMAGE_PREDICATE = """
            i.created_at <= ?
            AND NOT EXISTS (SELECT 1 FROM public_posts p WHERE p.image_id = i.id)
            """;

    private static final String SELECT_POSTS = """
            SELECT p.id, p.image_id
            FROM public_posts p
            WHERE %s
            ORDER BY COALESCE(p.deleted_at, p.hidden_at), p.id
            LIMIT ?
            FOR UPDATE OF p SKIP LOCKED
            """.formatted(POST_PREDICATE);

    private static final String SELECT_SWEEP_IMAGES = """
            SELECT i.id, i.storage_key
            FROM public_images i
            WHERE %s
            ORDER BY i.created_at, i.id
            LIMIT ?
            FOR UPDATE OF i SKIP LOCKED
            """.formatted(SWEEP_IMAGE_PREDICATE);

    private static final String DELETE_COMMENTS = """
            WITH candidates AS MATERIALIZED (
                SELECT c.ctid
                FROM public_comments c
                WHERE %s
                ORDER BY COALESCE(c.deleted_at, c.hidden_at), c.id
                LIMIT ?
                FOR UPDATE OF c SKIP LOCKED
            ), deleted AS (
                DELETE FROM public_comments target USING candidates c
                WHERE target.ctid = c.ctid
                RETURNING 1
            )
            SELECT count(*) FROM deleted
            """.formatted(COMMENT_PREDICATE);

    private static final String SELECT_IMAGE_KEY_IF_UNREFERENCED = """
            SELECT i.storage_key FROM public_images i
            WHERE i.id = ?
              AND NOT EXISTS (SELECT 1 FROM public_posts p WHERE p.image_id = i.id)
            """;

    private static final String DELETE_IMAGE_IF_UNREFERENCED = """
            DELETE FROM public_images i
            WHERE i.id = ?
              AND NOT EXISTS (SELECT 1 FROM public_posts p WHERE p.image_id = i.id)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;
    private final RetentionProperties properties;

    RetentionFeedCleaner(JdbcTemplate jdbcTemplate,
                         StorageService storageService,
                         RetentionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageService = storageService;
        this.properties = properties;
    }

    RetentionJobRunner.JobRunResult run(boolean dryRun) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RetentionProperties.PublicFeed config = properties.getJobs().getPublicFeed();
        OffsetDateTime purgeCutoff = now.minusDays(config.getRetentionDays());
        OffsetDateTime unboundCutoff = now.minusHours(config.getUnboundHours());
        int batchSize = properties.getBatchSize();

        if (dryRun) {
            Integer posts = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT 1 FROM public_posts p WHERE %s
                        ORDER BY COALESCE(p.deleted_at, p.hidden_at), p.id
                        LIMIT ?
                    ) selected
                    """.formatted(POST_PREDICATE), Integer.class,
                    purgeCutoff, purgeCutoff, batchSize);
            Integer comments = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT 1 FROM public_comments c WHERE %s
                        ORDER BY COALESCE(c.deleted_at, c.hidden_at), c.id
                        LIMIT ?
                    ) selected
                    """.formatted(COMMENT_PREDICATE), Integer.class,
                    purgeCutoff, purgeCutoff, batchSize);
            Integer images = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT 1 FROM public_images i WHERE %s
                        ORDER BY i.created_at, i.id
                        LIMIT ?
                    ) selected
                    """.formatted(SWEEP_IMAGE_PREDICATE), Integer.class,
                    unboundCutoff, batchSize);
            return new RetentionJobRunner.JobRunResult(
                    count(posts) + count(comments) + count(images), 0, 0);
        }

        int rows = 0;
        int files = 0;
        int errors = 0;
        // Image ids already handled by the purge phase in this run: the sweep
        // must not attempt them again, so one failed object delete counts as
        // one error per image per run.
        Set<UUID> handledImages = new HashSet<>();

        Integer comments = jdbcTemplate.queryForObject(DELETE_COMMENTS, Integer.class,
                purgeCutoff, purgeCutoff, batchSize);
        rows += count(comments);

        List<PostRow> posts = jdbcTemplate.query(SELECT_POSTS,
                (rs, rowNum) -> new PostRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("image_id", UUID.class)),
                purgeCutoff, purgeCutoff, batchSize);
        for (PostRow post : posts) {
            try {
                rows += jdbcTemplate.update("DELETE FROM public_posts WHERE id = ?", post.id());
                if (post.imageId() != null) {
                    handledImages.add(post.imageId());
                    List<String> keys = jdbcTemplate.queryForList(
                            SELECT_IMAGE_KEY_IF_UNREFERENCED, String.class, post.imageId());
                    for (String key : keys) {
                        Boolean removed = deleteObject(key);
                        if (removed == null) {
                            // The row stays so the next run retries the object delete.
                            errors++;
                        } else {
                            if (removed) {
                                files++;
                            }
                            rows += jdbcTemplate.update(DELETE_IMAGE_IF_UNREFERENCED,
                                    post.imageId());
                        }
                    }
                }
            } catch (RuntimeException failure) {
                errors++;
                log.warn("Retention feed cleanup skipped one post ({})",
                        failure.getClass().getSimpleName());
            }
        }

        List<FileRow> images = jdbcTemplate.query(SELECT_SWEEP_IMAGES,
                (rs, rowNum) -> new FileRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("storage_key")),
                unboundCutoff, batchSize);
        for (FileRow image : images) {
            if (!handledImages.add(image.id())) {
                // Already handled by the purge phase in this run.
                continue;
            }
            try {
                Boolean removed = deleteObject(image.storageKey());
                if (removed == null) {
                    // The row stays so the next run retries the object delete.
                    errors++;
                } else {
                    if (removed) {
                        files++;
                    }
                    // Same guard as the purge path: a post that bound the
                    // image in the meantime keeps its row.
                    if (jdbcTemplate.update(DELETE_IMAGE_IF_UNREFERENCED, image.id()) == 1) {
                        rows++;
                    }
                }
            } catch (RuntimeException failure) {
                errors++;
                log.warn("Retention feed cleanup skipped one image ({})",
                        failure.getClass().getSimpleName());
            }
        }

        return new RetentionJobRunner.JobRunResult(rows, files, errors);
    }

    /**
     * Deletes the stored object and reports what happened: {@code true} when
     * an object was actually removed, {@code false} when it was already
     * absent (a retry can then finish the row), and {@code null} on failure
     * (the row must stay so the next run retries).
     */
    private Boolean deleteObject(String storageKey) {
        try {
            return storageService.deleteIfExists(storageKey);
        } catch (RuntimeException failure) {
            log.warn("Retention feed cleanup could not delete one object ({})",
                    failure.getClass().getSimpleName());
            return null;
        }
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }

    private record PostRow(UUID id, UUID imageId) {
    }

    private record FileRow(UUID id, String storageKey) {
    }
}
