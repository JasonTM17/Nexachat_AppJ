package com.nexachat.label;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** JDBC access for private labels and their per-member assignments. */
@Repository
public class LabelRepository implements InboxLabelReader {

    private static final String LABEL_COLUMNS = "id, name, color_id, created_at, updated_at";

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public LabelRepository(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    public List<LabelResponse> findAllByUserId(UUID userId) {
        return jdbcTemplate().query("SELECT " + LABEL_COLUMNS
                        + " FROM conversation_labels WHERE user_id = ?"
                        + " ORDER BY lower(name), id",
                this::mapLabel, userId);
    }

    public Optional<LabelResponse> findByIdAndUserId(UUID labelId, UUID userId) {
        List<LabelResponse> rows = jdbcTemplate().query("SELECT " + LABEL_COLUMNS
                        + " FROM conversation_labels WHERE id = ? AND user_id = ?",
                this::mapLabel, labelId, userId);
        return rows.stream().findFirst();
    }

    /** Holds the owned label against deletion until the caller's transaction completes. */
    public Optional<LabelResponse> findByIdAndUserIdForKeyShare(UUID labelId, UUID userId) {
        List<LabelResponse> rows = jdbcTemplate().query("SELECT " + LABEL_COLUMNS
                        + " FROM conversation_labels WHERE id = ? AND user_id = ? FOR KEY SHARE",
                this::mapLabel, labelId, userId);
        return rows.stream().findFirst();
    }

    public long countByUserId(UUID userId) {
        Long count = jdbcTemplate().queryForObject(
                "SELECT count(*) FROM conversation_labels WHERE user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    public boolean existsName(UUID userId, String normalizedName) {
        Boolean exists = jdbcTemplate().queryForObject("""
                SELECT EXISTS (SELECT 1 FROM conversation_labels
                               WHERE user_id = ? AND lower(name) = lower(?))
                """, Boolean.class, userId, normalizedName);
        return Boolean.TRUE.equals(exists);
    }

    public boolean existsNameOtherThan(UUID userId, String normalizedName, UUID labelId) {
        Boolean exists = jdbcTemplate().queryForObject("""
                SELECT EXISTS (SELECT 1 FROM conversation_labels
                               WHERE user_id = ? AND lower(name) = lower(?) AND id <> ?)
                """, Boolean.class, userId, normalizedName, labelId);
        return Boolean.TRUE.equals(exists);
    }

    public void insert(UUID id, UUID userId, String name, String colorId) {
        jdbcTemplate().update("""
                INSERT INTO conversation_labels (id, user_id, name, color_id)
                VALUES (?, ?, ?, ?)
                """, id, userId, name, colorId);
    }

    public void update(UUID labelId, UUID userId, String name, String colorId) {
        jdbcTemplate().update("""
                UPDATE conversation_labels
                SET name = ?, color_id = ?, updated_at = now()
                WHERE id = ? AND user_id = ?
                """, name, colorId, labelId, userId);
    }

    public int delete(UUID labelId, UUID userId) {
        return jdbcTemplate().update(
                "DELETE FROM conversation_labels WHERE id = ? AND user_id = ?", labelId, userId);
    }

    public boolean hasAssignment(UUID conversationId, UUID userId, UUID labelId) {
        Boolean exists = jdbcTemplate().queryForObject("""
                SELECT EXISTS (SELECT 1 FROM conversation_label_assignments
                               WHERE conversation_id = ? AND user_id = ? AND label_id = ?)
                """, Boolean.class, conversationId, userId, labelId);
        return Boolean.TRUE.equals(exists);
    }

    public long countAssignments(UUID conversationId, UUID userId) {
        Long count = jdbcTemplate().queryForObject("""
                SELECT count(*) FROM conversation_label_assignments
                WHERE conversation_id = ? AND user_id = ?
                """, Long.class, conversationId, userId);
        return count == null ? 0 : count;
    }

    public void insertAssignment(UUID conversationId, UUID userId, UUID labelId) {
        jdbcTemplate().update("""
                INSERT INTO conversation_label_assignments (conversation_id, user_id, label_id)
                VALUES (?, ?, ?) ON CONFLICT (conversation_id, user_id, label_id) DO NOTHING
                """, conversationId, userId, labelId);
    }

    public void deleteAssignment(UUID conversationId, UUID userId, UUID labelId) {
        jdbcTemplate().update("""
                DELETE FROM conversation_label_assignments
                WHERE conversation_id = ? AND user_id = ? AND label_id = ?
                """, conversationId, userId, labelId);
    }

    @Override
    public Map<UUID, List<UUID>> findInboxLabelIds(UUID userId,
                                                   Collection<UUID> conversationIds) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null || conversationIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(
                conversationIds.size(), "?"));
        String sql = "SELECT conversation_id, label_id FROM conversation_label_assignments"
                + " WHERE user_id = ? AND conversation_id IN (" + placeholders + ")"
                + " ORDER BY conversation_id, label_id";
        List<Object> parameters = new ArrayList<>(conversationIds.size() + 1);
        parameters.add(userId);
        parameters.addAll(conversationIds);
        Map<UUID, List<UUID>> labelsByConversation = new LinkedHashMap<>();
        List<LabelAssignmentRow> assignments = jdbcTemplate.query(sql,
                (result, rowNumber) -> new LabelAssignmentRow(
                        result.getObject("conversation_id", UUID.class),
                        result.getObject("label_id", UUID.class)),
                parameters.toArray());
        for (LabelAssignmentRow assignment : assignments) {
            labelsByConversation.computeIfAbsent(assignment.conversationId(),
                    ignored -> new ArrayList<>()).add(assignment.labelId());
        }
        return labelsByConversation;
    }

    private JdbcTemplate jdbcTemplate() {
        return jdbcTemplateProvider.getObject();
    }

    private record LabelAssignmentRow(UUID conversationId, UUID labelId) {
    }

    private LabelResponse mapLabel(ResultSet result, int rowNumber) throws SQLException {
        Timestamp createdAt = result.getTimestamp("created_at");
        Timestamp updatedAt = result.getTimestamp("updated_at");
        return new LabelResponse(
                result.getObject("id", UUID.class),
                result.getString("name"),
                result.getString("color_id"),
                createdAt.toInstant(),
                updatedAt.toInstant());
    }
}
