package com.nexachat.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code alert_rules} access plus a tiny TTL cache for the hot enabled-rule
 * set. The scanner drains a bounded queue on a timer; caching the rule list
 * for {@code rulesCacheSeconds} keeps that loop off the database while still
 * converging quickly after an admin edits a rule (edits also bump the cache
 * explicitly via {@link #evictEnabledCache}).
 */
@Repository
public class AlertRuleRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AdminProperties properties;
    private final AtomicReference<List<AlertRuleRow>> enabledCache = new AtomicReference<>();
    private final AtomicLong enabledCachedAt = new AtomicLong();

    public AlertRuleRepository(JdbcTemplate jdbcTemplate, AdminProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<AlertRuleRow> findAll() {
        return jdbcTemplate.query("""
                SELECT id, name, category, severity, pattern_type, pattern, locale,
                       built_in, enabled, description, created_by, created_at, updated_at
                FROM alert_rules ORDER BY built_in DESC, category, name
                """, this::map);
    }

    public Optional<AlertRuleRow> findById(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, name, category, severity, pattern_type, pattern, locale,
                       built_in, enabled, description, created_by, created_at, updated_at
                FROM alert_rules WHERE id = ?
                """, this::map, id).stream().findFirst();
    }

    /**
     * Rule names for a batch of matched-rule ids in one query; ids without a
     * row (a deleted custom rule) are simply absent from the map and the
     * caller falls back to the raw id.
     */
    public Map<UUID, String> findNamesByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbcTemplate.query("""
                SELECT id, name FROM alert_rules WHERE id = ANY(?)
                """, statement -> statement.setArray(1,
                        statement.getConnection().createArrayOf("uuid", ids.toArray(UUID[]::new))),
                rs -> {
                    Map<UUID, String> names = new HashMap<>();
                    while (rs.next()) {
                        names.put(rs.getObject("id", UUID.class), rs.getString("name"));
                    }
                    return names;
                });
    }

    /** The enabled rule set, served from the short-lived cache when fresh. */
    public List<AlertRuleRow> enabledRules() {
        long ttlMillis = Math.max(1, properties.alerts().rulesCacheSeconds()) * 1000L;
        List<AlertRuleRow> cached = enabledCache.get();
        if (cached != null && System.currentTimeMillis() - enabledCachedAt.get() < ttlMillis) {
            return cached;
        }
        List<AlertRuleRow> fresh = jdbcTemplate.query("""
                SELECT id, name, category, severity, pattern_type, pattern, locale,
                       built_in, enabled, description, created_by, created_at, updated_at
                FROM alert_rules WHERE enabled ORDER BY id
                """, this::map);
        enabledCache.set(fresh);
        enabledCachedAt.set(System.currentTimeMillis());
        return fresh;
    }

    /** Called by the rule service after every mutation so scans pick it up now. */
    public void evictEnabledCache() {
        enabledCache.set(null);
    }

    public void insert(UUID id, String name, String category, String severity,
                       String patternType, String pattern, String locale,
                       boolean builtIn, String description, UUID createdBy) {
        jdbcTemplate.update("""
                INSERT INTO alert_rules
                    (id, name, category, severity, pattern_type, pattern, locale,
                     built_in, description, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, name, category, severity, patternType, pattern,
                locale == null ? "any" : locale, builtIn, description, createdBy);
        evictEnabledCache();
    }

    public void update(UUID id, String name, String severity, String pattern,
                       String description, boolean enabled) {
        jdbcTemplate.update("""
                UPDATE alert_rules
                SET name = ?, severity = ?, pattern = ?, description = ?,
                    enabled = ?, updated_at = now()
                WHERE id = ?
                """, name, severity, pattern, description, enabled, id);
        evictEnabledCache();
    }

    public void setEnabled(UUID id, boolean enabled) {
        jdbcTemplate.update(
                "UPDATE alert_rules SET enabled = ?, updated_at = now() WHERE id = ?",
                enabled, id);
        evictEnabledCache();
    }

    public int delete(UUID id) {
        int removed = jdbcTemplate.update("DELETE FROM alert_rules WHERE id = ?", id);
        evictEnabledCache();
        return removed;
    }

    private AlertRuleRow map(ResultSet rs, int rowNum) throws SQLException {
        return new AlertRuleRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("severity"),
                rs.getString("pattern_type"),
                rs.getString("pattern"),
                rs.getString("locale"),
                rs.getBoolean("built_in"),
                rs.getBoolean("enabled"),
                rs.getString("description"),
                rs.getObject("created_by", UUID.class),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
