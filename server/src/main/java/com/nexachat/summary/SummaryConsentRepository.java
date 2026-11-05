package com.nexachat.summary;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class SummaryConsentRepository {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public SummaryConsentRepository(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    public boolean isConsented(UUID userId) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return false;
        }
        Boolean consented = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND ai_summary_consent IS TRUE)",
                Boolean.class,
                userId);
        return Boolean.TRUE.equals(consented);
    }
}
