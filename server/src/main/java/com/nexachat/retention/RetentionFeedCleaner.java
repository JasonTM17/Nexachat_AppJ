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

/**
 * Purges public feed rows that are already invisible — soft-deleted by their
 * author or hidden by staff — once the retention window has passed, and sweeps
 * uploads that were never bound to a post.
 *
 * <p>Deleting a post cascades to its comments and reactions. The post's image
 * row is removed only when no other post references it, and the stored object
 * is deleted only when its row was actually removed, so a shared or re-bound
 * key is never orphaned.</p>
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

    private static final String UNBOUND_IMAGE_PREDICATE = """
            i.bound_at IS NULL
            AND i.created_at <= ?
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

    private static final String SELECT_UNBOUND_IMAGES = """
            SELECT i.id, i.storage_key
            FROM public_images i
            WHERE %s
            ORDER BY i.created_at, i.id
            LIMIT ?
            FOR UPDATE OF i SKIP LOCKED
            """.formatted(UNBOUND_IMAGE_PREDICATE);

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

    private static final String DELETE_IMAGE_IF_UNREFERENCED = """
            DELETE FROM public_images i
            WHERE i.id = ?
              AND NOT EXISTS (SELECT 1 FROM public_posts p WHERE p.image_id = i.id)
            RETURNING i.storage_key
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
                    """.formatted(UNBOUND_IMAGE_PREDICATE), Integer.class,
                    unboundCutoff, batchSize);
            return new RetentionJobRunner.JobRunResult(
                    count(posts) + count(comments) + count(images), 0, 0);
        }

        int rows = 0;
        int files = 0;
        int errors = 0;

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
                    List<String> keys = jdbcTemplate.queryForList(
                            DELETE_IMAGE_IF_UNREFERENCED, String.class, post.imageId());
                    rows += keys.size();
                    for (String key : keys) {
                        if (deleteObject(key)) {
                            files++;
                        }
                    }
                }
            } catch (RuntimeException failure) {
                errors++;
                log.warn("Retention feed cleanup skipped one post ({})",
                        failure.getClass().getSimpleName());
            }
        }

        List<FileRow> images = jdbcTemplate.query(SELECT_UNBOUND_IMAGES,
                (rs, rowNum) -> new FileRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("storage_key")),
                unboundCutoff, batchSize);
        for (FileRow image : images) {
            try {
                int deleted = jdbcTemplate.update(
                        "DELETE FROM public_images WHERE id = ?", image.id());
                if (deleted == 1) {
                    rows++;
                    if (deleteObject(image.storageKey())) {
                        files++;
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

    private boolean deleteObject(String storageKey) {
        try {
            return storageService.deleteIfExists(storageKey);
        } catch (RuntimeException failure) {
            log.warn("Retention feed cleanup could not delete one object ({})",
                    failure.getClass().getSimpleName());
            return false;
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
