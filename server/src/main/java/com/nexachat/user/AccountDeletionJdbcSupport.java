package com.nexachat.user;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** JDBC cleanup for tables without JPA repositories. */
@Component
class AccountDeletionJdbcSupport {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    AccountDeletionJdbcSupport(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    private JdbcTemplate jdbc() {
        return jdbcTemplateProvider.getIfAvailable();
    }

    void scrubAiData(UUID userId) {
        JdbcTemplate jdbcTemplate = jdbc();
        if (jdbcTemplate == null) {
            return;
        }
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
        JdbcTemplate jdbcTemplate = jdbc();
        if (jdbcTemplate == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM group_join_requests WHERE user_id = ?", userId);
    }

    void deleteLabels(UUID userId) {
        JdbcTemplate jdbcTemplate = jdbc();
        if (jdbcTemplate == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM conversation_labels WHERE user_id = ?", userId);
    }

    void deleteGoogleIdentityData(UUID userId) {
        JdbcTemplate jdbcTemplate = jdbc();
        if (jdbcTemplate == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM google_signin_nonces WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_identities WHERE user_id = ?", userId);
    }

    void deleteEmailOtps(UUID userId) {
        JdbcTemplate jdbcTemplate = jdbc();
        if (jdbcTemplate == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM email_otps WHERE user_id = ?", userId);
    }

    /**
     * Deletes uploads that were never bound to a message and returns the
     * storage keys whose rows this call actually removed.
     *
     * <p>{@code SKIP LOCKED} is the lock-order guard: a send that is claiming
     * one of these rows already holds its row lock and will reference the
     * sender row next. Deleting must not wait for such a row (the sender row
     * is held by the deletion transaction), and the returned keys must not
     * include a row that survived, or post-commit cleanup would delete a file
     * a message now owns. Skipped rows stay for the retention job.</p>
     */
    List<String> deleteUnboundAttachments(UUID ownerId) {
        JdbcTemplate jdbcTemplate = jdbc();
        if (jdbcTemplate == null) {
            return Collections.emptyList();
        }
        return jdbcTemplate.queryForList("""
                WITH candidates AS (
                    SELECT id
                    FROM message_attachments
                    WHERE owner_id = ? AND message_id IS NULL
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM message_attachments attachment
                USING candidates
                WHERE attachment.id = candidates.id
                RETURNING attachment.storage_key
                """, String.class, ownerId);
    }
}
