package com.nexachat.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JDBC cleanup for tables without JPA repositories. */
@Component
class AccountDeletionJdbcSupport {

    private final JdbcTemplate jdbcTemplate;

    AccountDeletionJdbcSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void scrubAiData(UUID userId) {
        jdbcTemplate.update("DELETE FROM conversation_ai_opt_outs WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM summary_request_limits WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM rag_ask_request_limits WHERE user_id = ?", userId);
        jdbcTemplate.update("""
                DELETE FROM message_summaries
                WHERE conversation_id IN (
                    SELECT conversation_id FROM conversation_members WHERE user_id = ?
                )
                """, userId);
        jdbcTemplate.update("""
                DELETE FROM rag_ask_cache
                WHERE conversation_id IN (
                    SELECT conversation_id FROM conversation_members WHERE user_id = ?
                )
                """, userId);
    }

    void deleteGroupJoinRequests(UUID userId) {
        jdbcTemplate.update("DELETE FROM group_join_requests WHERE user_id = ?", userId);
    }

    void deleteLabels(UUID userId) {
        jdbcTemplate.update("DELETE FROM conversation_labels WHERE user_id = ?", userId);
    }

    List<String> findUnboundAttachmentStorageKeys(UUID ownerId) {
        return jdbcTemplate.queryForList("""
                SELECT storage_key FROM message_attachments
                WHERE owner_id = ? AND message_id IS NULL
                """, String.class, ownerId);
    }

    void deleteUnboundAttachments(UUID ownerId) {
        jdbcTemplate.update(
                "DELETE FROM message_attachments WHERE owner_id = ? AND message_id IS NULL", ownerId);
    }
}
