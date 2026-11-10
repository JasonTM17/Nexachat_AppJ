package com.nexachat.user;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** JDBC cleanup for tables without JPA repositories (IT/prod only). */
@Component
@ConditionalOnBean(JdbcTemplate.class)
class AccountDeletionJdbcSupport {

    private final JdbcTemplate jdbcTemplate;

    AccountDeletionJdbcSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void scrubAiData(UUID userId) {
        jdbcTemplate.update("DELETE FROM conversation_ai_opt_outs WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM summary_request_limits WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM rag_ask_request_limits WHERE user_id = ?", userId);
    }
}
