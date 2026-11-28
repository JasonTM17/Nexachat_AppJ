package com.nexachat.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.testutil.FeatureItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff moderation of the public feed: the report queue, hide/unhide for posts
 * and comments, report resolution, the role gate, and the audit rows every
 * action leaves behind.
 */
class FeedAdminFlowIT extends FeatureItSupport {

    @MockitoSpyBean
    private com.nexachat.admin.AdminAuditService auditSpy;

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

    // These suites exercise the enabled feed: the runtime Social switches
    // ship off by default, so each test opts back in after the shared reset.
    // The kill-switch and video-gate denial paths live in SocialSettingsFlowIT.
    @BeforeEach
    void enableSocial() {
        enableSocialFeatures();
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

        postAs(moderator, "/api/v1/admin/feed/posts/" + postId + "/hide",
                Map.of("reason", "bad\u0000reason"))
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

    // ------------------------------------------------------- video preview

    // feed_video_h264_64x48.mp4: synthetic one-second solid-color clip; regen:
    //   ffmpeg -f lavfi -i color=c=0x336699:s=64x48:d=1:r=10 -an -c:v libx264     //       -pix_fmt yuv420p -movflags +faststart -frames:v 10 feed_video_h264_64x48.mp4
    private static byte[] videoBytes() {
        try (java.io.InputStream in = FeedAdminFlowIT.class.getResourceAsStream(
                "/fixtures/feed_video_h264_64x48.mp4")) {
            if (in == null) {
                throw new IllegalStateException("feed video fixture missing");
            }
            return in.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not read feed video fixture", failure);
        }
    }

    private UUID uploadVideo(Actor uploader) throws Exception {
        return UUID.fromString(json(mockMvc.perform(multipart("/api/social/v1/videos")
                        .file(new MockMultipartFile("file", "clip.mp4", "video/mp4", videoBytes()))
                        .header("Authorization", bearer(uploader)))
                .andExpect(status().isCreated())).get("videoId").asText());
    }

    @Test
    void reportedVideoIsStaffOnlyAuditedAndUnavailableAfterDelete() throws Exception {
        Actor author = newUser("Video Report Author");
        Actor reader = newUser("Video Report Reader");
        Actor reporter = newUser("Video Reporter");
        Actor moderator = withRole(newUser("Video Moderator"), "MODERATOR");

        UUID videoId = uploadVideo(author);
        UUID postId = UUID.fromString(json(postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "reported clip", "videoId", videoId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());
        // An ordinary reader can play it through the public route.
        getAs(reader, "/api/social/v1/videos/" + videoId).andExpect(status().isOk());

        UUID reportId = report(reporter, "POST", postId);
        UUID commentId = createComment(reader, postId, "a comment");
        UUID commentReportId = report(reporter, "COMMENT", commentId);
        UUID textOnlyPostId = createPost(author, "just words");
        UUID textReportId = report(reporter, "POST", textOnlyPostId);

        // The queue flags the video report and only the video report.
        JsonNode page = json(getAs(moderator, "/api/v1/admin/feed/reports")
                .andExpect(status().isOk()));
        boolean videoFlagged = false;
        for (JsonNode item : page.get("items")) {
            boolean hasVideo = item.get("targetHasVideo").asBoolean();
            if (item.get("id").asText().equals(reportId.toString())) {
                assertThat(hasVideo).isTrue();
                videoFlagged = true;
            } else {
                assertThat(hasVideo).isFalse();
            }
        }
        assertThat(videoFlagged).isTrue();

        // Anonymous and plain users never reach the preview.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/admin/feed/reports/" + reportId + "/video"))
                .andExpect(status().isUnauthorized());
        postAs(reporter, "/api/v1/admin/feed/reports/" + reportId + "/video", null)
                .andExpect(status().isForbidden());

        // Unknown report, comment report, and text-only post report: 404, no audit.
        postAs(moderator, "/api/v1/admin/feed/reports/" + UUID.randomUUID() + "/video", null)
                .andExpect(status().isNotFound());
        postAs(moderator, "/api/v1/admin/feed/reports/" + commentReportId + "/video", null)
                .andExpect(status().isNotFound());
        postAs(moderator, "/api/v1/admin/feed/reports/" + textReportId + "/video", null)
                .andExpect(status().isNotFound());
        assertThat(auditRows("FEED_REPORT_VIDEO_VIEW", reportId)).isZero();

        // Hiding the post removes public access but staff can still preview.
        postAs(moderator, "/api/v1/admin/feed/posts/" + postId + "/hide",
                Map.of("reason", "review"))
                .andExpect(status().isNoContent());
        getAs(reader, "/api/social/v1/videos/" + videoId).andExpect(status().isNotFound());

        postAs(moderator, "/api/v1/admin/feed/reports/" + reportId + "/video", null)
                .andExpect(status().isOk())
                .andExpect(result -> {
                    assertThat(result.getResponse().getContentType()).isEqualTo("video/mp4");
                    assertThat(result.getResponse().getContentAsByteArray())
                            .isEqualTo(videoBytes());
                    assertThat(result.getResponse().getHeader("Cache-Control"))
                            .contains("no-store");
                    assertThat(result.getResponse().getHeader("X-Content-Type-Options"))
                            .isEqualTo("nosniff");
                    assertThat(result.getResponse().getHeader("Content-Length"))
                            .isEqualTo(String.valueOf(videoBytes().length));
                });
        assertThat(auditRows("FEED_REPORT_VIDEO_VIEW", reportId)).isEqualTo(1);

        // A hidden post is invisible even to its author, so staff unhide
        // before the author can delete it; once deleted there is nothing left
        // to preview, and the denial is not audited as a view.
        postAs(moderator, "/api/v1/admin/feed/posts/" + postId + "/unhide", null)
                .andExpect(status().isNoContent());
        deleteAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isNoContent());
        postAs(moderator, "/api/v1/admin/feed/reports/" + reportId + "/video", null)
                .andExpect(status().isNotFound());
        assertThat(auditRows("FEED_REPORT_VIDEO_VIEW", reportId)).isEqualTo(1);
    }

    @Test
    void failedAuditYieldsNoVideoBytes() throws Exception {
        Actor author = newUser("Audit Fail Author");
        Actor reporter = newUser("Audit Fail Reporter");
        Actor moderator = withRole(newUser("Audit Fail Moderator"), "MODERATOR");

        UUID videoId = uploadVideo(author);
        UUID postId = UUID.fromString(json(postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "audit fail clip", "videoId", videoId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());
        UUID reportId = report(reporter, "POST", postId);

        // The audit row is the preview grant: when it cannot be written the
        // transaction rolls back and the storage read never happens.
        org.mockito.Mockito.doThrow(new IllegalStateException("audit down"))
                .when(auditSpy).record(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("FEED_REPORT_VIDEO_VIEW"),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        postAs(moderator, "/api/v1/admin/feed/reports/" + reportId + "/video", null)
                .andExpect(status().is5xxServerError());
        assertThat(auditRows("FEED_REPORT_VIDEO_VIEW", reportId)).isZero();
    }

    @Test
    void hidingPostDeniesOwnerMediaReadAndCommentLeakWhileStaffVideoPreviewWorks() throws Exception {
        Actor author = newUser("Mod Media Author");
        Actor commenter = newUser("Mod Commenter");
        Actor bystander = newUser("Mod Bystander");
        Actor reporter = newUser("Mod Reporter");
        Actor moderator = withRole(newUser("Mod Staff"), "MODERATOR");

        UUID imageId = UUID.fromString(json(mockMvc.perform(multipart("/api/v1/feed/images")
                        .file(new MockMultipartFile("file", "photo.png", "image/png",
                                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                                        0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
                                        0, 0, 0, 16, 0, 0, 0, 16, 8, 6, 0, 0, 0}))
                        .header("Authorization", bearer(author)))
                .andExpect(status().isCreated())).get("imageId").asText());
        UUID imagePostId = UUID.fromString(json(postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "hidden image post", "imageId", imageId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());

        UUID videoId = uploadVideo(author);
        UUID videoPostId = UUID.fromString(json(postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "hidden video post", "videoId", videoId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());
        UUID commentId = createComment(commenter, videoPostId, "comment under hidden post");
        UUID reportId = report(reporter, "POST", videoPostId);

        String imageKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM public_images WHERE id = ?", String.class, imageId);
        String videoKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM public_images WHERE id = ?", String.class, videoId);

        postAs(moderator, "/api/v1/admin/feed/posts/" + imagePostId + "/hide",
                Map.of("reason", "policy"))
                .andExpect(status().isNoContent());
        postAs(moderator, "/api/v1/admin/feed/posts/" + videoPostId + "/hide",
                Map.of("reason", "policy"))
                .andExpect(status().isNoContent());

        // Owner read of bound media on hidden posts must be 404 across all public endpoints.
        getAs(author, "/api/v1/feed/images/" + imageId).andExpect(status().isNotFound());
        getAs(author, "/api/social/v1/images/" + imageId).andExpect(status().isNotFound());
        getAs(author, "/api/v1/files/" + imageKey).andExpect(status().isNotFound());
        getAs(author, "/api/v1/feed/videos/" + videoId).andExpect(status().isNotFound());
        getAs(author, "/api/social/v1/videos/" + videoId).andExpect(status().isNotFound());
        getAs(author, "/api/v1/files/" + videoKey).andExpect(status().isNotFound());

        // Staff preview on the hidden reported video still works.
        postAs(moderator, "/api/v1/admin/feed/reports/" + reportId + "/video", null)
                .andExpect(status().isOk());

        // Comment delete/edit under the hidden post yields 404 (never 403).
        deleteAs(bystander, "/api/v1/feed/comments/" + commentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        deleteAs(commenter, "/api/v1/feed/comments/" + commentId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        patchAs(commenter, "/api/v1/feed/comments/" + commentId, Map.of("body", "edit hidden"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
    }
}
