package com.nexachat.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.testutil.FeatureItSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff moderation of the public feed: the report queue, hide/unhide for posts
 * and comments, report resolution, the role gate, and the audit rows every
 * action leaves behind.
 */
class FeedAdminFlowIT extends FeatureItSupport {

    private UUID createPost(Actor author, String body) throws Exception {
        return UUID.fromString(json(postAs(author, "/api/v1/feed/posts", Map.of(
                "body", body, "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());
    }

    private UUID createComment(Actor author, UUID postId, String body) throws Exception {
        return UUID.fromString(json(postAs(author,
                "/api/v1/feed/posts/" + postId + "/comments",
                Map.of("body", body, "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());
    }

    private UUID report(Actor reporter, String targetType, UUID targetId) throws Exception {
        return UUID.fromString(json(postAs(reporter, "/api/v1/reports", Map.of(
                "targetType", targetType,
                "targetId", targetId.toString(),
                "reason", "SPAM"))
                .andExpect(status().isCreated())).get("id").asText());
    }

    private int auditRows(String action, UUID targetId) {
        return countRows(
                "SELECT count(*) FROM admin_audit_log WHERE action = ? AND target_id = ?",
                action, targetId.toString());
    }

    @Test
    void reportQueueListsFeedReportsWithTargetState() throws Exception {
        Actor author = newUser("Queue Author");
        Actor reporter = newUser("Queue Reporter");
        Actor moderator = withRole(newUser("Queue Moderator"), "MODERATOR");
        UUID postId = createPost(author, "reported words");
        UUID reportId = report(reporter, "POST", postId);

        JsonNode page = json(getAs(moderator, "/api/v1/admin/feed/reports?status=OPEN")
                .andExpect(status().isOk()));

        assertThat(page.get("total").asLong()).isEqualTo(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("id").asText()).isEqualTo(reportId.toString());
        assertThat(item.get("targetType").asText()).isEqualTo("POST");
        assertThat(item.get("targetId").asText()).isEqualTo(postId.toString());
        assertThat(item.get("reporterId").asText()).isEqualTo(reporter.id().toString());
        assertThat(item.get("authorId").asText()).isEqualTo(author.id().toString());
        assertThat(item.get("targetBody").asText()).isEqualTo("reported words");
        assertThat(item.get("targetHidden").asBoolean()).isFalse();
        assertThat(item.get("targetDeleted").asBoolean()).isFalse();
    }

    @Test
    void hidingAPostRemovesItFromTheFeedAndIsAudited() throws Exception {
        Actor author = newUser("Hidden Author");
        Actor reader = newUser("Hidden Reader");
        Actor moderator = withRole(newUser("Hiding Moderator"), "MODERATOR");
        UUID postId = createPost(author, "hide me");

        postAs(moderator, "/api/v1/admin/feed/posts/" + postId + "/hide",
                Map.of("reason", "harassment"))
                .andExpect(status().isNoContent());

        getAs(reader, "/api/v1/feed/posts/" + postId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
        assertThat(countRows(
                "SELECT count(*) FROM public_posts WHERE id = ? AND hidden_at IS NOT NULL",
                postId)).isEqualTo(1);
        assertThat(auditRows("FEED_POST_HIDE", postId)).isEqualTo(1);

        postAs(moderator, "/api/v1/admin/feed/posts/" + postId + "/unhide", null)
                .andExpect(status().isNoContent());

        getAs(reader, "/api/v1/feed/posts/" + postId).andExpect(status().isOk());
        assertThat(auditRows("FEED_POST_UNHIDE", postId)).isEqualTo(1);
    }

    @Test
    void hidingACommentRemovesItFromTheThreadAndIsAudited() throws Exception {
        Actor author = newUser("Comment Hide Host");
        Actor moderator = withRole(newUser("Comment Moderator"), "MODERATOR");
        UUID postId = createPost(author, "thread");
        UUID commentId = createComment(author, postId, "hide this comment");

        postAs(moderator, "/api/v1/admin/feed/comments/" + commentId + "/hide",
                Map.of("reason", "spam"))
                .andExpect(status().isNoContent());

        JsonNode page = json(getAs(author, "/api/v1/feed/posts/" + postId + "/comments")
                .andExpect(status().isOk()));
        assertThat(page.get("items")).isEmpty();
        assertThat(auditRows("FEED_COMMENT_HIDE", commentId)).isEqualTo(1);

        postAs(moderator, "/api/v1/admin/feed/comments/" + commentId + "/unhide", null)
                .andExpect(status().isNoContent());

        JsonNode restored = json(getAs(author, "/api/v1/feed/posts/" + postId + "/comments")
                .andExpect(status().isOk()));
        assertThat(restored.get("items")).hasSize(1);
        assertThat(auditRows("FEED_COMMENT_UNHIDE", commentId)).isEqualTo(1);
    }

    @Test
    void hidingAndUnhidingACommentMovesThePostCommentCountOnlyOnChange() throws Exception {
        Actor author = newUser("Count Host");
        Actor moderator = withRole(newUser("Count Moderator"), "MODERATOR");
        UUID postId = createPost(author, "count me");
        UUID first = createComment(author, postId, "one");
        createComment(author, postId, "two");

        assertThat(commentCount(postId)).isEqualTo(2);

        postAs(moderator, "/api/v1/admin/feed/comments/" + first + "/hide",
                Map.of("reason", "spam"))
                .andExpect(status().isNoContent());
        assertThat(commentCount(postId)).isEqualTo(1);
        assertThat(json(getAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isOk()))
                .get("commentCount").asInt()).isEqualTo(1);

        postAs(moderator, "/api/v1/admin/feed/comments/" + first + "/hide",
                Map.of("reason", "spam"))
                .andExpect(status().isNoContent());
        assertThat(commentCount(postId)).isEqualTo(1);
        assertThat(auditRows("FEED_COMMENT_HIDE", first)).isEqualTo(1);

        postAs(moderator, "/api/v1/admin/feed/comments/" + first + "/unhide", null)
                .andExpect(status().isNoContent());
        assertThat(commentCount(postId)).isEqualTo(2);

        postAs(moderator, "/api/v1/admin/feed/comments/" + first + "/unhide", null)
                .andExpect(status().isNoContent());
        assertThat(commentCount(postId)).isEqualTo(2);
        assertThat(auditRows("FEED_COMMENT_UNHIDE", first)).isEqualTo(1);
    }

    @Test
    void hidingACommentItsAuthorDeletedIsANoOpAndKeepsTheCount() throws Exception {
        Actor author = newUser("Deleting Comment Author");
        Actor moderator = withRole(newUser("Late Hide Moderator"), "MODERATOR");
        UUID postId = createPost(author, "thread with a deleted comment");
        UUID deleted = createComment(author, postId, "delete me first");
        createComment(author, postId, "still visible");
        assertThat(commentCount(postId)).isEqualTo(2);

        deleteAs(author, "/api/v1/feed/comments/" + deleted).andExpect(status().isNoContent());
        assertThat(commentCount(postId)).isEqualTo(1);

        // The author's delete already moved the count, so a staff hide of the
        // deleted comment must be a no-op and may not decrement again.
        postAs(moderator, "/api/v1/admin/feed/comments/" + deleted + "/hide",
                Map.of("reason", "spam"))
                .andExpect(status().isNoContent());
        assertThat(commentCount(postId)).isEqualTo(1);
        assertThat(auditRows("FEED_COMMENT_HIDE", deleted)).isZero();

        postAs(moderator, "/api/v1/admin/feed/comments/" + deleted + "/unhide", null)
                .andExpect(status().isNoContent());
        assertThat(commentCount(postId)).isEqualTo(1);
        assertThat(auditRows("FEED_COMMENT_UNHIDE", deleted)).isZero();
    }

    private int commentCount(UUID postId) {
        return countRows("SELECT comment_count FROM public_posts WHERE id = ?", postId);
    }

    @Test
    void hideIsIdempotentAndKeepsOneAuditRow() throws Exception {
        Actor author = newUser("Idempotent Author");
        Actor moderator = withRole(newUser("Idempotent Moderator"), "MODERATOR");
        UUID postId = createPost(author, "hide twice");

        for (int attempt = 0; attempt < 2; attempt++) {
            postAs(moderator, "/api/v1/admin/feed/posts/" + postId + "/hide",
                    Map.of("reason", "spam"))
                    .andExpect(status().isNoContent());
        }

        assertThat(auditRows("FEED_POST_HIDE", postId)).isEqualTo(1);
    }

    @Test
    void hideRequiresAReason() throws Exception {
        Actor author = newUser("No Reason Author");
        Actor moderator = withRole(newUser("No Reason Moderator"), "MODERATOR");
        UUID postId = createPost(author, "needs a reason");

        postAs(moderator, "/api/v1/admin/feed/posts/" + postId + "/hide",
                Map.of("reason", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void hidingAMissingPostIsNotFound() throws Exception {
        Actor moderator = withRole(newUser("Missing Moderator"), "MODERATOR");

        postAs(moderator, "/api/v1/admin/feed/posts/" + UUID.randomUUID() + "/hide",
                Map.of("reason", "spam"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
    }

    @Test
    void resolvingAReportMarksItAndIsAudited() throws Exception {
        Actor author = newUser("Resolve Author");
        Actor reporter = newUser("Resolve Reporter");
        Actor moderator = withRole(newUser("Resolve Moderator"), "MODERATOR");
        UUID postId = createPost(author, "resolve me");
        UUID reportId = report(reporter, "POST", postId);

        postAs(moderator, "/api/v1/admin/feed/reports/" + reportId + "/resolve",
                Map.of("status", "DISMISSED"))
                .andExpect(status().isNoContent());

        assertThat(countRows("SELECT count(*) FROM reports WHERE id = ? AND status = 'DISMISSED'",
                reportId)).isEqualTo(1);
        assertThat(auditRows("FEED_REPORT_RESOLVE", reportId)).isEqualTo(1);

        JsonNode open = json(getAs(moderator, "/api/v1/admin/feed/reports?status=OPEN")
                .andExpect(status().isOk()));
        assertThat(open.get("total").asLong()).isZero();
    }

    @Test
    void resolvingWithAnUnknownStatusIsRejected() throws Exception {
        Actor author = newUser("Bad Status Author");
        Actor reporter = newUser("Bad Status Reporter");
        Actor moderator = withRole(newUser("Bad Status Moderator"), "MODERATOR");
        UUID postId = createPost(author, "status");
        UUID reportId = report(reporter, "POST", postId);

        postAs(moderator, "/api/v1/admin/feed/reports/" + reportId + "/resolve",
                Map.of("status", "OPEN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void plainUsersCannotReachTheAdminFeed() throws Exception {
        Actor author = newUser("Plain Author");
        Actor plain = newUser("Plain User");
        UUID postId = createPost(author, "not yours to hide");

        getAs(plain, "/api/v1/admin/feed/reports").andExpect(status().isForbidden());
        postAs(plain, "/api/v1/admin/feed/posts/" + postId + "/hide",
                Map.of("reason", "spam"))
                .andExpect(status().isForbidden());
        assertThat(auditRows("FEED_POST_HIDE", postId)).isZero();
    }
}
