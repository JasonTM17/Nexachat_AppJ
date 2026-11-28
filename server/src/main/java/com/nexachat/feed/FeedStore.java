package com.nexachat.feed;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for the public feed. Reads are one query per page plus one reaction
 * aggregate for the whole page, so a feed screen never issues a query per
 * post.
 *
 * <p><b>Visibility</b> is the same predicate everywhere: the row is not
 * soft-deleted, not staff-hidden, its author is not deleted, and no block edge
 * exists in either direction between the caller and the author. A row that
 * fails it is indistinguishable from a missing one.
 */
@Component
class FeedStore {

    /** One post joined with its author and image. */
    record PostRow(UUID id, UUID authorId, String authorName, String avatarUrl, String body,
                   UUID imageId, String imageKey, String imageMime, Integer imageWidth,
                   Integer imageHeight, int commentCount, Instant createdAt, Instant editedAt) {
    }

    /** One comment joined with its author. */
    record CommentRow(UUID id, UUID postId, UUID authorId, String authorName, String avatarUrl,
                      String body, Instant createdAt, Instant editedAt) {
    }

    /** One author profile visible to the caller. */
    record AuthorProfileRow(UUID userId, String displayName, String avatarUrl, long postCount) {
    }

    /** One uploaded feed image. */
    record ImageRow(UUID id, UUID ownerId, String storageKey, String mimeType, long sizeBytes,
                    Integer width, Integer height, Instant boundAt) {
    }

    /** One reaction aggregate row. */
    record ReactionRow(UUID postId, String emoji, int count, boolean mine) {
    }

    private static final String POST_COLUMNS = """
            SELECT p.id, p.author_id, u.display_name, u.avatar_url, p.body, p.image_id,
                   i.storage_key AS image_key, i.mime_type AS image_mime,
                   i.width AS image_width, i.height AS image_height,
                   p.comment_count, p.created_at, p.edited_at
            FROM public_posts p
            JOIN users u ON u.id = p.author_id
            LEFT JOIN public_images i ON i.id = p.image_id
            """;

    private static final String POST_VISIBLE = """
            WHERE p.deleted_at IS NULL AND p.hidden_at IS NULL AND u.deleted_at IS NULL
              AND NOT EXISTS (
                    SELECT 1 FROM user_blocks b
                    WHERE (b.blocker_id = :viewer AND b.blocked_id = p.author_id)
                       OR (b.blocker_id = p.author_id AND b.blocked_id = :viewer))
            """;

    private static final String COMMENT_COLUMNS = """
            SELECT c.id, c.post_id, c.author_id, u.display_name, u.avatar_url, c.body,
                   c.created_at, c.edited_at
            FROM public_comments c
            JOIN users u ON u.id = c.author_id
            """;

    private static final String COMMENT_VISIBLE = """
            WHERE c.deleted_at IS NULL AND c.hidden_at IS NULL AND u.deleted_at IS NULL
              AND NOT EXISTS (
                    SELECT 1 FROM user_blocks b
                    WHERE (b.blocker_id = :viewer AND b.blocked_id = c.author_id)
                       OR (b.blocker_id = c.author_id AND b.blocked_id = :viewer))
            """;

    private static final String PARENT_POST_VISIBLE = """
              AND EXISTS (
                    SELECT 1 FROM public_posts p
                    JOIN users pu ON pu.id = p.author_id
                    WHERE p.id = c.post_id
                      AND p.deleted_at IS NULL AND p.hidden_at IS NULL
                      AND pu.deleted_at IS NULL
                      AND NOT EXISTS (
                            SELECT 1 FROM user_blocks b
                            WHERE (b.blocker_id = :viewer AND b.blocked_id = p.author_id)
                               OR (b.blocker_id = p.author_id AND b.blocked_id = :viewer)))
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    FeedStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    // ------------------------------------------------------------------- posts

    List<PostRow> listPosts(UUID viewerId, UUID authorId, String likePattern,
                            FeedCursor.Position cursor, int limit) {
        StringBuilder sql = new StringBuilder(POST_COLUMNS).append(POST_VISIBLE);
        MapSqlParameterSource params = new MapSqlParameterSource("viewer", viewerId)
                .addValue("limit", limit);
        if (authorId != null) {
            sql.append("  AND p.author_id = :authorId\n");
            params.addValue("authorId", authorId);
        }
        if (likePattern != null) {
            sql.append("  AND p.body IS NOT NULL AND public.f_unaccent(p.body) ILIKE public.f_unaccent(:pattern) ESCAPE chr(92)\n");
            params.addValue("pattern", likePattern);
        }
        if (cursor != null) {
            sql.append("  AND (p.created_at, p.id) < (:cursorCreatedAt, :cursorId)\n");
            params.addValue("cursorCreatedAt", timestamp(cursor.createdAt()))
                    .addValue("cursorId", cursor.id());
        }
        sql.append("""
                ORDER BY p.created_at DESC, p.id DESC
                LIMIT :limit
                """);
        return named.query(sql.toString(), params, postMapper());
    }

    Optional<PostRow> findVisiblePost(UUID viewerId, UUID postId) {
        return Optional.ofNullable(named.query(POST_COLUMNS + POST_VISIBLE + " AND p.id = :postId",
                new MapSqlParameterSource("viewer", viewerId).addValue("postId", postId),
                single(postMapper())));
    }

    /** The author's own row for an idempotent create replay; hidden/deleted rows are gone. */
    Optional<PostRow> findOwnPostByClientId(UUID authorId, UUID clientPostId) {
        return Optional.ofNullable(named.query(POST_COLUMNS + POST_VISIBLE + """
                          AND p.author_id = :authorId AND p.client_post_id = :clientPostId
                        """,
                new MapSqlParameterSource("viewer", authorId)
                        .addValue("authorId", authorId)
                        .addValue("clientPostId", clientPostId),
                single(postMapper())));
    }

    boolean existsDeadPostByClientId(UUID authorId, UUID clientPostId) {
        Boolean found = jdbc.query("""
                        SELECT TRUE FROM public_posts
                        WHERE author_id = ? AND client_post_id = ?
                          AND (deleted_at IS NOT NULL OR hidden_at IS NOT NULL)
                        LIMIT 1
                        """,
                rs -> rs.next() ? Boolean.TRUE : Boolean.FALSE,
                authorId, clientPostId);
        return Boolean.TRUE.equals(found);
    }

    int insertPost(UUID id, UUID authorId, UUID clientPostId, String body, UUID imageId,
                   Instant createdAt) {
        return named.update("""
                        INSERT INTO public_posts
                            (id, author_id, client_post_id, body, image_id, created_at)
                        VALUES (:id, :authorId, :clientPostId, :body, :imageId, :createdAt)
                        ON CONFLICT (author_id, client_post_id) DO NOTHING
                        """,
                new MapSqlParameterSource("id", id)
                        .addValue("authorId", authorId)
                        .addValue("clientPostId", clientPostId)
                        .addValue("body", body)
                        .addValue("imageId", imageId)
                        .addValue("createdAt", timestamp(createdAt)));
    }

    /** The visible author of a post, or empty when it is gone, hidden or blocked. */
    Optional<UUID> findVisiblePostAuthor(UUID viewerId, UUID postId) {
        return Optional.ofNullable(named.query("""
                        SELECT p.author_id FROM public_posts p
                        JOIN users u ON u.id = p.author_id
                        """ + POST_VISIBLE + " AND p.id = :postId",
                new MapSqlParameterSource("viewer", viewerId).addValue("postId", postId),
                rs -> rs.next() ? rs.getObject("author_id", UUID.class) : null));
    }

    int updatePostBody(UUID postId, UUID authorId, String body, Instant editedAt) {
        return jdbc.update("""
                        UPDATE public_posts SET body = ?, edited_at = ?
                        WHERE id = ? AND author_id = ? AND deleted_at IS NULL AND hidden_at IS NULL
                        """,
                body, timestamp(editedAt), postId, authorId);
    }

    int softDeletePost(UUID postId, UUID authorId, Instant now) {
        return jdbc.update("""
                        UPDATE public_posts SET deleted_at = ?
                        WHERE id = ? AND author_id = ? AND deleted_at IS NULL AND hidden_at IS NULL
                        """,
                timestamp(now), postId, authorId);
    }

    Optional<AuthorProfileRow> findVisibleAuthorProfile(UUID viewerId, UUID authorId) {
        return Optional.ofNullable(named.query("""
                        SELECT u.id, u.display_name, u.avatar_url,
                               (SELECT count(*) FROM public_posts p
                                WHERE p.author_id = u.id
                                  AND p.deleted_at IS NULL
                                  AND p.hidden_at IS NULL) AS post_count
                        FROM users u
                        WHERE u.id = :authorId
                          AND u.deleted_at IS NULL
                          AND NOT EXISTS (
                                SELECT 1 FROM user_blocks b
                                WHERE (b.blocker_id = :viewer AND b.blocked_id = u.id)
                                   OR (b.blocker_id = u.id AND b.blocked_id = :viewer))
                        """,
                new MapSqlParameterSource("viewer", viewerId).addValue("authorId", authorId),
                single((rs, rowNum) -> new AuthorProfileRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("avatar_url"),
                        rs.getLong("post_count")))));
    }

    // ---------------------------------------------------------------- comments

    List<CommentRow> listCommentsFirst(UUID viewerId, UUID postId, int limit) {
        return named.query(COMMENT_COLUMNS + COMMENT_VISIBLE + """
                          AND c.post_id = :postId
                        ORDER BY c.created_at ASC, c.id ASC
                        LIMIT :limit
                        """,
                new MapSqlParameterSource("viewer", viewerId)
                        .addValue("postId", postId)
                        .addValue("limit", limit),
                commentMapper());
    }

    List<CommentRow> listCommentsAfter(UUID viewerId, UUID postId, FeedCursor.Position cursor,
                                       int limit) {
        return named.query(COMMENT_COLUMNS + COMMENT_VISIBLE + """
                          AND c.post_id = :postId
                          AND (c.created_at, c.id) > (:cursorCreatedAt, :cursorId)
                        ORDER BY c.created_at ASC, c.id ASC
                        LIMIT :limit
                        """,
                new MapSqlParameterSource("viewer", viewerId)
                        .addValue("postId", postId)
                        .addValue("cursorCreatedAt", timestamp(cursor.createdAt()))
                        .addValue("cursorId", cursor.id())
                        .addValue("limit", limit),
                commentMapper());
    }

    Optional<CommentRow> findVisibleComment(UUID viewerId, UUID commentId) {
        return Optional.ofNullable(named.query(
                COMMENT_COLUMNS + COMMENT_VISIBLE + PARENT_POST_VISIBLE + " AND c.id = :commentId",
                new MapSqlParameterSource("viewer", viewerId).addValue("commentId", commentId),
                single(commentMapper())));
    }

    Optional<CommentRow> findOwnCommentByClientId(UUID authorId, UUID postId, UUID clientCommentId) {
        return Optional.ofNullable(named.query(COMMENT_COLUMNS + COMMENT_VISIBLE + PARENT_POST_VISIBLE + """
                          AND c.author_id = :authorId AND c.post_id = :postId
                          AND c.client_comment_id = :clientCommentId
                        """,
                new MapSqlParameterSource("viewer", authorId)
                        .addValue("authorId", authorId)
                        .addValue("postId", postId)
                        .addValue("clientCommentId", clientCommentId),
                single(commentMapper())));
    }

    boolean existsDeadOrCrossPostCommentByClientId(UUID authorId, UUID postId, UUID clientCommentId) {
        Boolean found = jdbc.query("""
                        SELECT TRUE FROM public_comments
                        WHERE author_id = ? AND client_comment_id = ?
                          AND (deleted_at IS NOT NULL OR hidden_at IS NOT NULL OR post_id <> ?)
                        LIMIT 1
                        """,
                rs -> rs.next() ? Boolean.TRUE : Boolean.FALSE,
                authorId, clientCommentId, postId);
        return Boolean.TRUE.equals(found);
    }

    int insertComment(UUID id, UUID postId, UUID authorId, UUID clientCommentId, String body,
                      Instant createdAt) {
        return named.update("""
                        INSERT INTO public_comments
                            (id, post_id, author_id, client_comment_id, body, created_at)
                        VALUES (:id, :postId, :authorId, :clientCommentId, :body, :createdAt)
                        ON CONFLICT (author_id, client_comment_id) DO NOTHING
                        """,
                new MapSqlParameterSource("id", id)
                        .addValue("postId", postId)
                        .addValue("authorId", authorId)
                        .addValue("clientCommentId", clientCommentId)
                        .addValue("body", body)
                        .addValue("createdAt", timestamp(createdAt)));
    }

    void incrementCommentCount(UUID postId) {
        jdbc.update("UPDATE public_posts SET comment_count = comment_count + 1 WHERE id = ?", postId);
    }

    void decrementCommentCount(UUID postId) {
        jdbc.update("""
                UPDATE public_posts SET comment_count = GREATEST(comment_count - 1, 0) WHERE id = ?
                """, postId);
    }

    Optional<UUID> findVisibleCommentAuthor(UUID viewerId, UUID commentId) {
        return Optional.ofNullable(named.query("""
                        SELECT c.author_id FROM public_comments c
                        JOIN users u ON u.id = c.author_id
                        """ + COMMENT_VISIBLE + PARENT_POST_VISIBLE + " AND c.id = :commentId",
                new MapSqlParameterSource("viewer", viewerId).addValue("commentId", commentId),
                rs -> rs.next() ? rs.getObject("author_id", UUID.class) : null));
    }

    Optional<UUID> findCommentPostId(UUID commentId) {
        return jdbc.query("SELECT post_id FROM public_comments WHERE id = ?",
                rs -> rs.next() ? Optional.of(rs.getObject("post_id", UUID.class))
                        : Optional.empty(), commentId);
    }

    int updateCommentBody(UUID commentId, UUID authorId, String body, Instant editedAt) {
        return named.update("""
                        UPDATE public_comments c SET body = :body, edited_at = :editedAt
                        WHERE c.id = :commentId AND c.author_id = :authorId
                          AND c.deleted_at IS NULL AND c.hidden_at IS NULL
                        """ + PARENT_POST_VISIBLE,
                new MapSqlParameterSource("body", body)
                        .addValue("editedAt", timestamp(editedAt))
                        .addValue("commentId", commentId)
                        .addValue("authorId", authorId)
                        .addValue("viewer", authorId));
    }

    int softDeleteComment(UUID commentId, UUID authorId, Instant now) {
        return named.update("""
                        UPDATE public_comments c SET deleted_at = :now
                        WHERE c.id = :commentId AND c.author_id = :authorId
                          AND c.deleted_at IS NULL AND c.hidden_at IS NULL
                        """ + PARENT_POST_VISIBLE,
                new MapSqlParameterSource("now", timestamp(now))
                        .addValue("commentId", commentId)
                        .addValue("authorId", authorId)
                        .addValue("viewer", authorId));
    }

    // --------------------------------------------------------------- reactions

    Map<UUID, List<ReactionRow>> reactionsFor(Collection<UUID> postIds, UUID viewerId) {
        Map<UUID, List<ReactionRow>> result = new HashMap<>();
        if (postIds.isEmpty()) {
            return result;
        }
        named.query("""
                        SELECT post_id, emoji, count(*) AS reaction_count,
                               bool_or(user_id = :viewer) AS mine
                        FROM public_post_reactions
                        WHERE post_id IN (:postIds)
                        GROUP BY post_id, emoji
                        ORDER BY post_id, emoji
                        """,
                new MapSqlParameterSource("viewer", viewerId).addValue("postIds", postIds),
                rs -> {
                    result.computeIfAbsent(rs.getObject("post_id", UUID.class),
                                    ignored -> new java.util.ArrayList<>())
                            .add(new ReactionRow(rs.getObject("post_id", UUID.class),
                                    rs.getString("emoji"), rs.getInt("reaction_count"),
                                    rs.getBoolean("mine")));
                });
        return result;
    }

    List<ReactionRow> reactionsFor(UUID postId, UUID viewerId) {
        return reactionsFor(List.of(postId), viewerId).getOrDefault(postId, List.of());
    }

    int addReaction(UUID postId, UUID userId, String emoji, int maxDistinct) {
        return jdbc.update("""
                INSERT INTO public_post_reactions (post_id, user_id, emoji)
                SELECT ?, ?, ?
                WHERE NOT EXISTS (
                        SELECT 1 FROM public_post_reactions
                        WHERE post_id = ? AND user_id = ? AND emoji = ?)
                  AND (SELECT count(*) FROM public_post_reactions
                        WHERE post_id = ? AND user_id = ?) < ?
                ON CONFLICT DO NOTHING
                """, postId, userId, emoji, postId, userId, emoji,
                postId, userId, maxDistinct);
    }

    boolean hasReaction(UUID postId, UUID userId, String emoji) {
        Boolean found = jdbc.query("""
                        SELECT TRUE FROM public_post_reactions
                        WHERE post_id = ? AND user_id = ? AND emoji = ?
                        """,
                rs -> rs.next() ? Boolean.TRUE : Boolean.FALSE,
                postId, userId, emoji);
        return Boolean.TRUE.equals(found);
    }

    int removeReaction(UUID postId, UUID userId, String emoji) {
        return jdbc.update("""
                DELETE FROM public_post_reactions
                WHERE post_id = ? AND user_id = ? AND emoji = ?
                """, postId, userId, emoji);
    }

    // ------------------------------------------------------------------ images

    void insertImage(UUID id, UUID ownerId, String storageKey, String mimeType, long sizeBytes,
                     Integer width, Integer height, Instant createdAt) {
        jdbc.update("""
                        INSERT INTO public_images
                            (id, owner_id, storage_key, mime_type, size_bytes, width, height,
                             created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, ownerId, storageKey, mimeType, sizeBytes, width, height, timestamp(createdAt));
    }

    Optional<ImageRow> findImage(UUID imageId) {
        return Optional.ofNullable(jdbc.query("""
                        SELECT id, owner_id, storage_key, mime_type, size_bytes, width, height,
                               bound_at
                        FROM public_images WHERE id = ?
                        """, single(imageMapper()), imageId));
    }

    Optional<ImageRow> findImageByStorageKey(String storageKey) {
        return Optional.ofNullable(jdbc.query("""
                        SELECT id, owner_id, storage_key, mime_type, size_bytes, width, height,
                               bound_at
                        FROM public_images WHERE storage_key = ?
                        """, single(imageMapper()), storageKey));
    }

    /** How many uploaded images still wait to be bound to a post. */
    int countUnboundImages(UUID ownerId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM public_images WHERE owner_id = ? AND bound_at IS NULL
                """, Integer.class, ownerId);
        return count == null ? 0 : count;
    }

    /** Binds an unbound upload to the post that just referenced it. */
    int bindImage(UUID imageId, UUID ownerId) {
        return jdbc.update("""
                UPDATE public_images SET bound_at = now()
                WHERE id = ? AND owner_id = ? AND bound_at IS NULL
                """, imageId, ownerId);
    }

    /** Whether the image is attached to a post the viewer can see. */
    boolean isImageVisibleTo(UUID imageId, UUID viewerId) {
        Boolean visible = named.query("""
                        SELECT TRUE
                        FROM public_images i
                        JOIN public_posts p ON p.image_id = i.id
                        JOIN users u ON u.id = p.author_id
                        WHERE i.id = :imageId
                          AND p.deleted_at IS NULL AND p.hidden_at IS NULL
                          AND u.deleted_at IS NULL
                          AND NOT EXISTS (
                                SELECT 1 FROM user_blocks b
                                WHERE (b.blocker_id = :viewer AND b.blocked_id = p.author_id)
                                   OR (b.blocker_id = p.author_id AND b.blocked_id = :viewer))
                        LIMIT 1
                        """,
                new MapSqlParameterSource("imageId", imageId).addValue("viewer", viewerId),
                rs -> rs.next() ? Boolean.TRUE : Boolean.FALSE);
        return Boolean.TRUE.equals(visible);
    }

    // ------------------------------------------------------- report snapshots

    /**
     * The body of a comment the reporter can see. The parent post must be
     * visible to the reporter too — not deleted or hidden, its author alive
     * and not blocked in either direction — matching what the comment list
     * shows.
     */
    Optional<String> visibleCommentBody(UUID viewerId, UUID commentId) {
        return Optional.ofNullable(named.query(
                COMMENT_COLUMNS + COMMENT_VISIBLE + PARENT_POST_VISIBLE + " AND c.id = :commentId",
                new MapSqlParameterSource("viewer", viewerId).addValue("commentId", commentId),
                single(commentMapper()))).map(CommentRow::body);
    }

    // ----------------------------------------------------------------- mapping

    private static RowMapper<PostRow> postMapper() {
        return (rs, rowNum) -> new PostRow(
                rs.getObject("id", UUID.class),
                rs.getObject("author_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("avatar_url"),
                rs.getString("body"),
                rs.getObject("image_id", UUID.class),
                rs.getString("image_key"),
                rs.getString("image_mime"),
                rs.getObject("image_width", Integer.class),
                rs.getObject("image_height", Integer.class),
                rs.getInt("comment_count"),
                instant(rs, "created_at"),
                instant(rs, "edited_at"));
    }

    private static RowMapper<CommentRow> commentMapper() {
        return (rs, rowNum) -> new CommentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("post_id", UUID.class),
                rs.getObject("author_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("avatar_url"),
                rs.getString("body"),
                instant(rs, "created_at"),
                instant(rs, "edited_at"));
    }

    private static RowMapper<ImageRow> imageMapper() {
        return (rs, rowNum) -> {
            OffsetDateTime boundAt = rs.getObject("bound_at", OffsetDateTime.class);
            return new ImageRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("owner_id", UUID.class),
                    rs.getString("storage_key"),
                    rs.getString("mime_type"),
                    rs.getLong("size_bytes"),
                    rs.getObject("width", Integer.class),
                    rs.getObject("height", Integer.class),
                    boundAt == null ? null : boundAt.toInstant());
        };
    }

    private static <T> ResultSetExtractor<T> single(RowMapper<T> mapper) {
        return rs -> rs.next() ? mapper.mapRow(rs, 0) : null;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
