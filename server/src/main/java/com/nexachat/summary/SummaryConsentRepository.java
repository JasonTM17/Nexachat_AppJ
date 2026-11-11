package com.nexachat.summary;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class SummaryConsentRepository {

    private static final String SNAPSHOT_SQL = """
            SELECT ai_summary_consent, ai_summary_consent_epoch
            FROM users
            WHERE id = ?
            """;
    private static final String LOCKED_SNAPSHOT_SQL = SNAPSHOT_SQL + " FOR SHARE";

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public SummaryConsentRepository(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    public boolean isConsented(UUID userId) {
        return snapshot(userId).consented();
    }

    public ConsentSnapshot snapshot(UUID userId) {
        return readSnapshot(userId, SNAPSHOT_SQL);
    }

    /**
     * Reads the current consent snapshot while holding a shared row lock until
     * the caller's transaction completes. Settings updates use a conflicting
     * write lock, allowing an HTTP response writer to order revocation against
     * the final response flush.
     */
    public ConsentSnapshot lockForResponse(UUID userId) {
        return readSnapshot(userId, LOCKED_SNAPSHOT_SQL);
    }

    private ConsentSnapshot readSnapshot(UUID userId, String sql) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return new ConsentSnapshot(false, -1);
        }
        List<ConsentSnapshot> snapshots = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ConsentSnapshot(rs.getBoolean("ai_summary_consent"),
                        rs.getLong("ai_summary_consent_epoch")),
                userId);
        return snapshots.stream().findFirst().orElse(new ConsentSnapshot(false, -1));
    }

    public record ConsentSnapshot(boolean consented, long epoch) {
    }
}
