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
        // The opt-out DELETE fires the V47 ai-context trigger per row, which
        // locks each affected conversations row in scan order. Pre-locking
        // the distinct set in id order keeps every transaction's parent-lock
        // order conversations-first, so two concurrent deletions sharing
        // opted-out conversations cannot invert each other.
        jdbcTemplate.queryForList("""
                SELECT conv.id FROM conversations conv
                WHERE conv.id IN (
                    SELECT DISTINCT conversation_id FROM conversation_ai_opt_outs WHERE user_id = ?
                )
                ORDER BY conv.id FOR UPDATE
                """, UUID.class, userId);
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
     * Cancels the account's still-PENDING scheduled messages. Users are
     * tombstoned, never row-deleted, so the sender FK's cascade can never
     * clean these up — without this the dispatcher would keep retrying a
     * ghost's queue until each row dead-letters (Wukong amendment 1). The
     * update also drops the claim pair and scrubs the body, matching every
     * other terminal transition; a row mid-dispatch holds its row lock, and
     * the serialised outcome (send commits -> PENDING predicate misses, or
     * this wins -> the claim token dies with it) is safe either way.
     */
    void cancelPendingScheduledMessages(UUID userId) {
        JdbcTemplate jdbcTemplate = jdbc();
        if (jdbcTemplate == null) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE scheduled_messages
                SET status = 'CANCELED', failure_code = 'ACCOUNT_DELETED', body = '',
                    claimed_at = NULL, claim_token = NULL, next_attempt_at = NULL,
                    updated_at = now()
                WHERE sender_id = ? AND status = 'PENDING'
                """, userId);
    }

    /**
     * Soft-deletes the account's public feed content and drops its reactions.
     *
     * <p>The rows stay for the retention job, which purges them together with
     * their stored images once the window has passed. Until then the feed's
     * visibility predicate already hides them, because it requires a live
     * author. Reactions are removed outright: they are the account's own
     * personal data and carry no content anyone else needs.</p>
     */
    void softDeleteFeedContent(UUID userId) {
        JdbcTemplate jdbcTemplate = jdbc();
        if (jdbcTemplate == null) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE public_posts SET deleted_at = now()
                WHERE author_id = ? AND deleted_at IS NULL
                """, userId);
        jdbcTemplate.update("""
                UPDATE public_comments SET deleted_at = now()
                WHERE author_id = ? AND deleted_at IS NULL
                """, userId);
        jdbcTemplate.update("DELETE FROM public_post_reactions WHERE user_id = ?", userId);
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

    /**
     * Deletes feed uploads that were never bound to a post and returns the
     * storage keys whose rows this call actually removed. Same lock-order
     * contract as {@link #deleteUnboundAttachments}: {@code SKIP LOCKED} lets
     * a concurrent post bind its image instead of making deletion wait.
     */
    List<String> deleteUnboundFeedImages(UUID ownerId) {
        JdbcTemplate jdbcTemplate = jdbc();
        if (jdbcTemplate == null) {
            return Collections.emptyList();
        }
        return jdbcTemplate.queryForList("""
                WITH candidates AS (
                    SELECT id
                    FROM public_images
                    WHERE owner_id = ? AND bound_at IS NULL
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM public_images image
                USING candidates
                WHERE image.id = candidates.id
                RETURNING image.storage_key
                """, String.class, ownerId);
    }
}
