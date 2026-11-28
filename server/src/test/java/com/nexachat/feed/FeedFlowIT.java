package com.nexachat.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.auth.RateLimitService;
import com.nexachat.testutil.FeatureItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the public feed against a real PostgreSQL: the
 * newest-first post page and its cursor, idempotent creates, the visibility
 * rules (own delete, other readers, blocks), flat comments, post reactions,
 * reporting a post, and the image path that only the feed serves.
 */
class FeedFlowIT extends FeatureItSupport {

    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A"
            + "0000000D4948445200000010000000100806000000");

    @Autowired
    private RateLimitService rateLimitService;

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(
                    value.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }

    private JsonNode createPost(Actor author, String body) throws Exception {
        return json(postAs(author, "/api/v1/feed/posts", Map.of(
                "body", body, "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()));
    }

    private UUID createPostId(Actor author, String body) throws Exception {
        return UUID.fromString(createPost(author, body).get("id").asText());
    }

    private JsonNode feed(Actor viewer, String query) throws Exception {
        return json(getAs(viewer, "/api/v1/feed/posts" + query).andExpect(status().isOk()));
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    // ------------------------------------------------------------------- posts

    // These suites exercise the enabled feed: the runtime Social switches
    // ship off by default, so each test opts back in after the shared reset.
    // The kill-switch and video-gate denial paths live in SocialSettingsFlowIT.
    @BeforeEach
    void enableSocial() {
        enableSocialFeatures();
    }

    @Test
    void createdPostIsReturnedWithItsAuthorAndFlags() throws Exception {
        Actor author = newUser("Feed Author");

        JsonNode post = createPost(author, "  hello feed  ");

        assertThat(post.get("body").asText()).isEqualTo("hello feed");
        assertThat(post.get("author").get("id").asText()).isEqualTo(author.id().toString());
        assertThat(post.get("author").get("displayName").asText()).isEqualTo("Feed Author");
        assertThat(post.get("mine").asBoolean()).isTrue();
        assertThat(post.get("commentCount").asInt()).isZero();
        assertThat(post.get("image").isNull()).isTrue();
        assertThat(post.get("reactions")).isEmpty();
        assertThat(post.get("createdAt").asText()).isNotBlank();
    }

    @Test
    void replayedClientPostIdReturnsTheStoredRow() throws Exception {
        Actor author = newUser("Replay Author");
        String clientPostId = UUID.randomUUID().toString();
        Map<String, Object> request = Map.of("body", "once", "clientPostId", clientPostId);

        JsonNode first = json(postAs(author, "/api/v1/feed/posts", request)
                .andExpect(status().isCreated()));
        JsonNode replay = json(postAs(author, "/api/v1/feed/posts", request)
                .andExpect(status().isOk()));

        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(countRows("SELECT count(*) FROM public_posts")).isEqualTo(1);
    }

    @Test
    void feedPageIsNewestFirstAndPagesByCursor() throws Exception {
        Actor author = newUser("Paging Author");
        Set<String> created = new HashSet<>();
        created.add(createPostId(author, "first").toString());
        created.add(createPostId(author, "second").toString());
        created.add(createPostId(author, "third").toString());

        JsonNode firstPage = feed(author, "?limit=2");
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("nextCursor").asText()).isNotBlank();
        assertThat(Instant.parse(firstPage.get("items").get(0).get("createdAt").asText()))
                .isAfterOrEqualTo(Instant.parse(
                        firstPage.get("items").get(1).get("createdAt").asText()));

        JsonNode secondPage = feed(author,
                "?limit=2&cursor=" + firstPage.get("nextCursor").asText());
        assertThat(secondPage.get("items")).hasSize(1);
        assertThat(secondPage.get("nextCursor").isNull()).isTrue();

        Set<String> seen = new HashSet<>(ids(firstPage));
        seen.addAll(ids(secondPage));
        assertThat(seen).isEqualTo(created);
    }

    @Test
    void malformedCursorIsRejected() throws Exception {
        Actor viewer = newUser("Cursor Viewer");

        getAs(viewer, "/api/v1/feed/posts?cursor=not-a-cursor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void postNeedsTextOrAnImage() throws Exception {
        Actor author = newUser("Empty Author");

        postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "   ", "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void postBodyOverTheLimitIsRejected() throws Exception {
        Actor author = newUser("Long Author");

        postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "x".repeat(1001), "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anotherUserCanReadButNotDeleteAPost() throws Exception {
        Actor author = newUser("Owner");
        Actor reader = newUser("Reader");
        UUID postId = createPostId(author, "public words");

        JsonNode read = json(getAs(reader, "/api/v1/feed/posts/" + postId)
                .andExpect(status().isOk()));
        assertThat(read.get("body").asText()).isEqualTo("public words");
        assertThat(read.get("mine").asBoolean()).isFalse();

        deleteAs(reader, "/api/v1/feed/posts/" + postId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        deleteAs(author, "/api/v1/feed/posts/" + postId)
                .andExpect(status().isNoContent());
    }

    @Test
    void deletedPostDisappearsFromTheFeedAndTheDetail() throws Exception {
        Actor author = newUser("Deleting Author");
        UUID postId = createPostId(author, "gone soon");

        deleteAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isNoContent());

        getAs(author, "/api/v1/feed/posts/" + postId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
        assertThat(ids(feed(author, ""))).doesNotContain(postId.toString());
        assertThat(countRows(
                "SELECT count(*) FROM public_posts WHERE id = ? AND deleted_at IS NOT NULL",
                postId)).isEqualTo(1);
    }

    @Test
    void blockedAuthorDisappearsFromTheFeed() throws Exception {
        Actor viewer = newUser("Blocking Viewer");
        Actor blocked = newUser("Blocked Author");
        UUID postId = createPostId(blocked, "not for you");

        postAs(viewer, "/api/v1/users/" + blocked.id() + "/block", null)
                .andExpect(status().is2xxSuccessful());

        assertThat(ids(feed(viewer, ""))).doesNotContain(postId.toString());
        getAs(viewer, "/api/v1/feed/posts/" + postId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
    }

    @Test
    void canonicalSocialMappingKeepsTheLegacyFeedContract() throws Exception {
        Actor author = newUser("Canonical Author");
        Actor reader = newUser("Canonical Reader");
        String clientPostId = UUID.randomUUID().toString();
        Map<String, Object> request = Map.of("body", "canonical seam", "clientPostId", clientPostId);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/social/v1/posts"))
                .andExpect(status().isUnauthorized());

        JsonNode created = json(postAs(author, "/api/social/v1/posts", request)
                .andExpect(status().isCreated()));
        assertThat(created.get("author").get("id").asText()).isEqualTo(author.id().toString());
        String postId = created.get("id").asText();

        JsonNode legacyReplay = json(postAs(author, "/api/v1/feed/posts", request)
                .andExpect(status().isOk()));
        assertThat(legacyReplay.get("id").asText()).isEqualTo(postId);

        JsonNode readerView = json(getAs(reader, "/api/social/v1/posts/" + postId)
                .andExpect(status().isOk()));
        assertThat(readerView.get("id").asText()).isEqualTo(postId);
        assertThat(readerView.get("mine").asBoolean()).isFalse();

        postAs(reader, "/api/v1/users/" + author.id() + "/block", null)
                .andExpect(status().is2xxSuccessful());
        getAs(reader, "/api/social/v1/posts/" + postId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
        getAs(reader, "/api/v1/feed/posts/" + postId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- comments

    @Test
    void commentsAreOldestFirstAndCounted() throws Exception {
        Actor author = newUser("Comment Host");
        Actor commenter = newUser("Commenter");
        UUID postId = createPostId(author, "talk to me");

        JsonNode first = json(postAs(commenter,
                "/api/v1/feed/posts/" + postId + "/comments",
                Map.of("body", "first", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()));
        JsonNode second = json(postAs(author,
                "/api/v1/feed/posts/" + postId + "/comments",
                Map.of("body", "second", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()));

        JsonNode page = json(getAs(author, "/api/v1/feed/posts/" + postId + "/comments")
                .andExpect(status().isOk()));
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("id").asText())
                .isEqualTo(first.get("id").asText());
        assertThat(page.get("items").get(1).get("id").asText())
                .isEqualTo(second.get("id").asText());
        assertThat(page.get("items").get(0).get("mine").asBoolean()).isFalse();
        assertThat(page.get("items").get(1).get("mine").asBoolean()).isTrue();
        assertThat(page.get("nextCursor").isNull()).isTrue();

        JsonNode post = json(getAs(author, "/api/v1/feed/posts/" + postId)
                .andExpect(status().isOk()));
        assertThat(post.get("commentCount").asInt()).isEqualTo(2);

        deleteAs(commenter, "/api/v1/feed/comments/" + first.get("id").asText())
                .andExpect(status().isNoContent());
        JsonNode afterDelete = json(getAs(author, "/api/v1/feed/posts/" + postId)
                .andExpect(status().isOk()));
        assertThat(afterDelete.get("commentCount").asInt()).isEqualTo(1);
    }

    @Test
    void commentReplayReturnsTheStoredRow() throws Exception {
        Actor author = newUser("Comment Replay Host");
        UUID postId = createPostId(author, "replay me");
        String clientCommentId = UUID.randomUUID().toString();
        Map<String, Object> request = Map.of("body", "once", "clientCommentId", clientCommentId);

        JsonNode first = json(postAs(author, "/api/v1/feed/posts/" + postId + "/comments",
                request).andExpect(status().isCreated()));
        JsonNode replay = json(postAs(author, "/api/v1/feed/posts/" + postId + "/comments",
                request).andExpect(status().isOk()));

        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(countRows("SELECT count(*) FROM public_comments")).isEqualTo(1);
        assertThat(countRows(
                "SELECT comment_count FROM public_posts WHERE id = ?", postId)).isEqualTo(1);
    }

    @Test
    void anotherUserCannotDeleteAComment() throws Exception {
        Actor author = newUser("Comment Owner");
        Actor other = newUser("Comment Bystander");
        UUID postId = createPostId(author, "mine");
        JsonNode comment = json(postAs(author, "/api/v1/feed/posts/" + postId + "/comments",
                Map.of("body", "mine too", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()));

        deleteAs(other, "/api/v1/feed/comments/" + comment.get("id").asText())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    // --------------------------------------------------------------- reactions

    @Test
    void reactionsAreIdempotentAndAggregated() throws Exception {
        Actor author = newUser("Reaction Host");
        Actor first = newUser("First Reactor");
        Actor second = newUser("Second Reactor");
        UUID postId = createPostId(author, "react here");
        String path = "/api/v1/feed/posts/" + postId + "/reactions/";

        putAs(first, path + "\uD83D\uDC4D").andExpect(status().isOk());
        JsonNode repeated = json(putAs(first, path + "\uD83D\uDC4D").andExpect(status().isOk()));
        assertThat(repeated.get("reactions")).hasSize(1);
        assertThat(repeated.get("reactions").get(0).get("count").asInt()).isEqualTo(1);
        assertThat(repeated.get("reactions").get(0).get("mine").asBoolean()).isTrue();

        JsonNode both = json(putAs(second, path + "\uD83D\uDC4D").andExpect(status().isOk()));
        assertThat(both.get("reactions").get(0).get("count").asInt()).isEqualTo(2);
        assertThat(both.get("reactions").get(0).get("mine").asBoolean()).isTrue();

        JsonNode asAuthor = json(getAs(author, "/api/v1/feed/posts/" + postId)
                .andExpect(status().isOk()));
        assertThat(asAuthor.get("reactions").get(0).get("count").asInt()).isEqualTo(2);
        assertThat(asAuthor.get("reactions").get(0).get("mine").asBoolean()).isFalse();

        JsonNode removed = json(deleteAs(first, path + "\uD83D\uDC4D").andExpect(status().isOk()));
        assertThat(removed.get("reactions").get(0).get("count").asInt()).isEqualTo(1);
        JsonNode removedAgain = json(deleteAs(first, path + "\uD83D\uDC4D")
                .andExpect(status().isOk()));
        assertThat(removedAgain.get("reactions").get(0).get("count").asInt()).isEqualTo(1);
    }

    @Test
    void reactionOnAnInvisiblePostIsNotFound() throws Exception {
        Actor author = newUser("Hidden Reaction Host");
        Actor other = newUser("Hidden Reaction Viewer");
        UUID postId = createPostId(author, "gone");
        deleteAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isNoContent());

        putAs(other, "/api/v1/feed/posts/" + postId + "/reactions/\uD83D\uDC4D")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
    }

    // ----------------------------------------------------------------- reports

    @Test
    void reportingAPostSnapshotsItsBody() throws Exception {
        Actor author = newUser("Reported Author");
        Actor reporter = newUser("Reporter");
        UUID postId = createPostId(author, "something rude");

        JsonNode report = json(postAs(reporter, "/api/v1/reports", Map.of(
                "targetType", "POST",
                "targetId", postId.toString(),
                "reason", "HARASSMENT",
                "details", "please look"))
                .andExpect(status().isCreated()));

        assertThat(report.get("targetType").asText()).isEqualTo("POST");
        assertThat(report.get("messageSnapshot").asText()).isEqualTo("something rude");
        assertThat(countRows(
                "SELECT count(*) FROM reports WHERE target_id = ? AND target_type = 'POST'",
                postId)).isEqualTo(1);
    }

    @Test
    void reportingACommentSnapshotsItsBody() throws Exception {
        Actor author = newUser("Comment Report Host");
        Actor reporter = newUser("Comment Reporter");
        UUID postId = createPostId(author, "host post");
        JsonNode comment = json(postAs(author, "/api/v1/feed/posts/" + postId + "/comments",
                Map.of("body", "rude comment", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()));

        JsonNode report = json(postAs(reporter, "/api/v1/reports", Map.of(
                "targetType", "COMMENT",
                "targetId", comment.get("id").asText(),
                "reason", "SPAM"))
                .andExpect(status().isCreated()));

        assertThat(report.get("messageSnapshot").asText()).isEqualTo("rude comment");
    }

    @Test
    void reportingADeletedPostIsNotFound() throws Exception {
        Actor author = newUser("Deleted Report Author");
        Actor reporter = newUser("Late Reporter");
        UUID postId = createPostId(author, "was here");
        deleteAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isNoContent());

        postAs(reporter, "/api/v1/reports", Map.of(
                "targetType", "POST",
                "targetId", postId.toString(),
                "reason", "SPAM"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
    }

    @Test
    void reportingACommentOnAPostWhoseAuthorBlockedTheReporterIsNotFound() throws Exception {
        Actor author = newUser("Blocking Post Author");
        Actor commenter = newUser("Third Party Commenter");
        Actor reporter = newUser("Blocked Reporter");
        UUID postId = createPostId(author, "author's post");
        JsonNode comment = json(postAs(commenter, "/api/v1/feed/posts/" + postId + "/comments",
                Map.of("body", "third party comment", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()));
        postAs(author, "/api/v1/users/" + reporter.id() + "/block", null)
                .andExpect(status().is2xxSuccessful());

        getAs(reporter, "/api/v1/feed/posts/" + postId + "/comments")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));

        postAs(reporter, "/api/v1/reports", Map.of(
                "targetType", "COMMENT",
                "targetId", comment.get("id").asText(),
                "reason", "SPAM"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
    }

    @Test
    void reportingACommentOnAPostByADeletedAuthorIsNotFound() throws Exception {
        Actor author = newUser("Deleted Post Author");
        Actor commenter = newUser("Surviving Commenter");
        Actor reporter = newUser("Late Reporter");
        UUID postId = createPostId(author, "doomed post");
        JsonNode comment = json(postAs(commenter, "/api/v1/feed/posts/" + postId + "/comments",
                Map.of("body", "comment on a doomed post",
                        "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()));
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", author.id());

        getAs(reporter, "/api/v1/feed/posts/" + postId + "/comments")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));

        postAs(reporter, "/api/v1/reports", Map.of(
                "targetType", "COMMENT",
                "targetId", comment.get("id").asText(),
                "reason", "SPAM"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ images

    @Test
    void feedImageIsServedOnlyThroughTheFeed() throws Exception {
        Actor author = newUser("Image Author");
        Actor reader = newUser("Image Reader");

        MvcResult upload = mockMvc.perform(multipart("/api/v1/feed/images")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES))
                        .param("width", "16")
                        .param("height", "16")
                        .header("Authorization", bearer(author)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode image = json(upload);
        String imageId = image.get("imageId").asText();
        assertThat(image.get("mimeType").asText()).isEqualTo("image/png");
        assertThat(image.get("url").asText()).isEqualTo("/api/v1/feed/images/" + imageId);
        assertThat(image.get("width").asInt()).isEqualTo(16);

        getAs(author, "/api/v1/feed/images/" + imageId).andExpect(status().isOk());
        getAs(reader, "/api/v1/feed/images/" + imageId).andExpect(status().isNotFound());

        UUID postId = UUID.fromString(json(postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "with a picture",
                "imageId", imageId,
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());

        getAs(reader, "/api/v1/feed/images/" + imageId)
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .isEqualTo("image/png"));

        String storageKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM public_images WHERE id = ?", String.class,
                UUID.fromString(imageId));
        getAs(reader, "/api/v1/files/" + storageKey).andExpect(status().isOk());

        deleteAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isNoContent());

        getAs(reader, "/api/v1/feed/images/" + imageId).andExpect(status().isNotFound());
        getAs(reader, "/api/v1/files/" + storageKey)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void feedImageUploadRejectsANonImage() throws Exception {
        Actor author = newUser("Bad Uploader");

        mockMvc.perform(multipart("/api/v1/feed/images")
                        .file(new MockMultipartFile("file", "evil.png", "image/png",
                                hex("4D5A90000300000004000000FFFF0000")))
                        .header("Authorization", bearer(author)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("MIME_NOT_ALLOWED"));
    }

    @Test
    void postRateLimitStopsTheFourthPostInAMinute() throws Exception {
        Actor author = newUser("Chatty Author");
        for (int index = 0; index < 3; index++) {
            createPost(author, "post " + index);
        }

        postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "one too many", "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void rejectedPostsDoNotBurnThePerMinuteLimit() throws Exception {
        Actor author = newUser("Sloppy Author");

        // Neither text nor an image, and images the author does not own: all
        // rejected by the service after the request passed bean validation.
        postAs(author, "/api/v1/feed/posts",
                Map.of("clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "hi", "imageId", UUID.randomUUID().toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "hi", "imageId", UUID.randomUUID().toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "valid after the rejections", "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectedCommentsDoNotBurnThePerMinuteLimit() throws Exception {
        Actor author = newUser("Quiet Host");
        Actor commenter = newUser("Noisy Commenter");
        UUID postId = createPostId(author, "host post");
        String commentsPath = "/api/v1/feed/posts/" + postId + "/comments";

        for (int index = 0; index < 10; index++) {
            postAs(commenter, "/api/v1/feed/posts/" + UUID.randomUUID() + "/comments",
                    Map.of("body", "hi", "clientCommentId", UUID.randomUUID().toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
        }

        postAs(commenter, commentsPath, Map.of(
                "body", "valid after the rejections", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
    }

    @Test
    void reactionsOnAnInvisiblePostDoNotBurnThePerMinuteLimit() throws Exception {
        Actor author = newUser("Quiet Post Host");
        Actor reactor = newUser("Impatient Reactor");
        UUID postId = createPostId(author, "react target");
        String missingPost = "/api/v1/feed/posts/" + UUID.randomUUID() + "/reactions/\uD83D\uDC4D";

        for (int index = 0; index < 60; index++) {
            putAs(reactor, missingPost).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
        }

        putAs(reactor, "/api/v1/feed/posts/" + postId + "/reactions/\uD83D\uDC4D")
                .andExpect(status().isOk());
    }

    @Test
    void invalidEmojiReactionsDoNotBurnThePerMinuteLimit() throws Exception {
        Actor author = newUser("Emoji Host");
        Actor reactor = newUser("Long Emoji Reactor");
        UUID postId = createPostId(author, "emoji target");
        String path = "/api/v1/feed/posts/" + postId + "/reactions/";

        for (int index = 0; index < 60; index++) {
            putAs(reactor, path + "x".repeat(17)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        putAs(reactor, path + "\uD83D\uDC4D").andExpect(status().isOk());
    }

    @Test
    void feedImageUploadIsRateLimitedPerUser() throws Exception {
        Actor author = newUser("Frequent Uploader");
        for (int index = 0; index < 10; index++) {
            uploadImage(author).andExpect(status().isCreated());
        }

        uploadImage(author)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void invalidImageUploadsDoNotBurnThePerMinuteLimit() throws Exception {
        Actor author = newUser("Sloppy Uploader");

        // Rejected uploads: non-image bytes, an empty file, absurd dimensions.
        for (int index = 0; index < 5; index++) {
            mockMvc.perform(multipart("/api/v1/feed/images")
                            .file(new MockMultipartFile("file", "evil.png", "image/png",
                                    hex("4D5A90000300000004000000FFFF0000")))
                            .header("Authorization", bearer(author)))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.error.code").value("MIME_NOT_ALLOWED"));
            mockMvc.perform(multipart("/api/v1/feed/images")
                            .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                            .header("Authorization", bearer(author)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(multipart("/api/v1/feed/images")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES))
                        .param("width", "20000")
                        .header("Authorization", bearer(author)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // A valid upload still succeeds right after the rejections: under the
        // old pre-validation quota order these ten rejections would have
        // drained the minute bucket and this upload would be 429.
        uploadImage(author).andExpect(status().isCreated());

        // Exactly one token was spent — by the valid upload alone.
        String bucket = "feed:image:minute:" + author.id();
        int remaining = 0;
        while (rateLimitService.tryConsume(bucket, FeedImageService.IMAGES_PER_MINUTE,
                Duration.ofMinutes(1)).allowed()) {
            remaining++;
        }
        assertThat(remaining).isEqualTo(FeedImageService.IMAGES_PER_MINUTE - 1);
    }

    @Test
    void feedImageUploadIsCappedWhenUnboundUploadsAccumulate() throws Exception {
        Actor author = newUser("Hoarding Uploader");
        for (int index = 0; index < 20; index++) {
            jdbcTemplate.update("""
                    INSERT INTO public_images
                        (id, owner_id, storage_key, mime_type, size_bytes, created_at)
                    VALUES (?, ?, ?, 'image/png', 10, now())
                    """, UUID.randomUUID(), author.id(), "it/" + UUID.randomUUID());
        }

        uploadImage(author)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    private ResultActions uploadImage(Actor uploader) throws Exception {
        return mockMvc.perform(multipart("/api/v1/feed/images")
                .file(new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES))
                .header("Authorization", bearer(uploader)));
    }

    @Test
    void reportingAnImageOnlyPostIsAcceptedWithANullSnapshot() throws Exception {
        Actor author = newUser("Image Poster");
        Actor reporter = newUser("Image Reporter");
        UUID imageId = UUID.fromString(json(uploadImage(author)
                        .andExpect(status().isCreated()).andReturn())
                .get("imageId").asText());
        String postId = json(postAs(author, "/api/v1/feed/posts", Map.of(
                        "imageId", imageId.toString(),
                        "clientPostId", UUID.randomUUID().toString()))
                        .andExpect(status().isCreated()).andReturn())
                .get("id").asText();

        postAs(reporter, "/api/v1/reports", Map.of(
                        "targetType", "POST", "targetId", postId, "reason", "OTHER"))
                .andExpect(status().isCreated());
    }

    @Test
    void replayedClientPostIdWithAnImageReturnsTheStoredRow() throws Exception {
        Actor author = newUser("Retrying Poster");
        UUID imageId = UUID.fromString(json(uploadImage(author)
                        .andExpect(status().isCreated()).andReturn())
                .get("imageId").asText());
        UUID clientPostId = UUID.randomUUID();
        Map<String, String> payload = Map.of(
                "body", "photo post", "imageId", imageId.toString(),
                "clientPostId", clientPostId.toString());
        String postId = json(postAs(author, "/api/v1/feed/posts", payload)
                        .andExpect(status().isCreated()).andReturn())
                .get("id").asText();

        postAs(author, "/api/v1/feed/posts", payload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(postId));
    }

    @Test
    void distinctReactionsPerUserPerPostAreCapped() throws Exception {
        Actor author = newUser("Post Host");
        Actor reactor = newUser("Prolific Reactor");
        UUID postId = createPostId(author, "react here");
        String path = "/api/v1/feed/posts/" + postId + "/reactions/";
        String[] emojis = {"👍", "❤️", "😂", "😮", "😢", "🔥", "👏", "🎉"};
        for (String emoji : emojis) {
            putAs(reactor, path + emoji).andExpect(status().isOk());
        }
        // re-adding an existing emoji stays idempotent under the cap
        putAs(reactor, path + "👍").andExpect(status().isOk());

        putAs(reactor, path + "🤯")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void aCapRejectedReactionDoesNotBurnThePerMinuteLimit() throws Exception {
        Actor author = newUser("Cap Host");
        Actor reactor = newUser("Capped Reactor");
        UUID postId = createPostId(author, "cap target");
        String path = "/api/v1/feed/posts/" + postId + "/reactions/";
        String[] emojis = {"👍", "❤️", "😂", "😮", "😢", "🔥", "👏", "🎉"};
        // Place the 8 distinct reactions directly so the request below is the
        // only one that can touch the per-minute reaction bucket.
        for (String emoji : emojis) {
            jdbcTemplate.update(
                    "INSERT INTO public_post_reactions (post_id, user_id, emoji) VALUES (?, ?, ?)",
                    postId, reactor.id(), emoji);
        }

        // The ninth distinct emoji is rejected by the cap; the rejection must
        // not use up one of the per-minute reaction tokens.
        putAs(reactor, path + "🤯")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // The bucket was never touched: all 60 tokens are still there.
        String bucket = "feed:reaction:minute:" + reactor.id();
        int remaining = 0;
        while (rateLimitService.tryConsume(bucket, FeedService.REACTIONS_PER_MINUTE,
                Duration.ofMinutes(1)).allowed()) {
            remaining++;
        }
        assertThat(remaining).isEqualTo(FeedService.REACTIONS_PER_MINUTE);
    }

    @Test
    void deletingAPostWhoseAuthorBlockedYouIsNotFound() throws Exception {
        Actor author = newUser("Blocking Author");
        Actor viewer = newUser("Blocked Deleter");
        UUID postId = createPostId(author, "blocked content");
        postAs(viewer, "/api/v1/users/" + author.id() + "/block", null)
                .andExpect(status().isNoContent());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/feed/posts/" + postId)
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
    }

    @Test
    void feedImageUploadRejectsAbsurdDimensions() throws Exception {
        Actor author = newUser("Dimension Cheater");

        mockMvc.perform(multipart("/api/v1/feed/images")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES))
                        .param("width", "20000").param("height", "4")
                        .header("Authorization", bearer(author)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
    // ------------------------------------------------------------------ videos

    // feed_video_h264_64x48.mp4 is a synthetic one-second solid-color clip with
    // no real content, regenerated deterministically with:
    //   ffmpeg -y -f lavfi -i color=c=0x336699:s=64x48:d=1:r=10 -an -c:v libx264
    //       -pix_fmt yuv420p -movflags +faststart -frames:v 10 feed_video_h264_64x48.mp4
    private static byte[] videoBytes() {
        try (java.io.InputStream in = FeedFlowIT.class.getResourceAsStream("/fixtures/feed_video_h264_64x48.mp4")) {
            if (in == null) {
                throw new IllegalStateException("feed video fixture missing");
            }
            return in.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not read feed video fixture", failure);
        }
    }

    private ResultActions uploadVideo(Actor uploader) throws Exception {
        return mockMvc.perform(multipart("/api/social/v1/videos")
                .file(new MockMultipartFile("file", "clip.mp4", "video/mp4", videoBytes()))
                .header("Authorization", bearer(uploader)));
    }

    private UUID uploadVideoId(Actor uploader) throws Exception {
        return UUID.fromString(json(uploadVideo(uploader)
                .andExpect(status().isCreated()).andReturn()).get("videoId").asText());
    }

    @Test
    void videoUploadPostAndPlaybackFollowTheFeedRules() throws Exception {
        Actor author = newUser("Video Author");
        Actor reader = newUser("Video Reader");

        MvcResult upload = uploadVideo(author).andExpect(status().isCreated()).andReturn();
        JsonNode asset = json(upload);
        String videoId = asset.get("videoId").asText();
        assertThat(asset.has("imageId")).isFalse();
        assertThat(asset.get("mimeType").asText()).isEqualTo("video/mp4");
        assertThat(asset.get("url").asText()).isEqualTo("/api/v1/feed/videos/" + videoId);

        // Unbound: the owner can read it back, nobody else can.
        getAs(author, "/api/social/v1/videos/" + videoId).andExpect(status().isOk());
        getAs(reader, "/api/social/v1/videos/" + videoId).andExpect(status().isNotFound());

        String clientPostId = UUID.randomUUID().toString();
        java.util.Map<String, String> payload = java.util.Map.of(
                "body", "watch this",
                "videoId", videoId,
                "clientPostId", clientPostId);
        JsonNode created = json(postAs(author, "/api/social/v1/posts", payload)
                .andExpect(status().isCreated()).andReturn());
        String postId = created.get("id").asText();
        assertThat(created.get("video").get("url").asText())
                .isEqualTo("/api/v1/feed/videos/" + videoId);
        assertThat(created.get("video").get("mimeType").asText()).isEqualTo("video/mp4");
        assertThat(created.get("image").isNull()).isTrue();
        assertThat(created.get("body").asText()).isEqualTo("watch this");

        // An idempotent replay returns the same row with the same video.
        JsonNode replay = json(postAs(author, "/api/social/v1/posts", payload)
                .andExpect(status().isOk()).andReturn());
        assertThat(replay.get("id").asText()).isEqualTo(postId);
        assertThat(countRows("SELECT count(*) FROM public_posts")).isEqualTo(1);

        // The reader sees the caption and the canonical video but no image.
        JsonNode feedPage = feed(reader, "?limit=5");
        JsonNode item = feedPage.get("items").get(0);
        assertThat(item.get("body").asText()).isEqualTo("watch this");
        assertThat(item.get("image").isNull()).isTrue();
        assertThat(item.get("video").get("url").asText())
                .isEqualTo("/api/v1/feed/videos/" + videoId);

        // Authenticated playback: exact bytes, video MIME, no-store.
        getAs(reader, "/api/social/v1/videos/" + videoId)
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

        // The image route refuses the same id and vice versa.
        getAs(reader, "/api/v1/feed/images/" + videoId).andExpect(status().isNotFound());

        // A block makes the video invisible again.
        postAs(reader, "/api/v1/users/" + author.id() + "/block", null)
                .andExpect(status().isNoContent());
        getAs(reader, "/api/social/v1/videos/" + videoId).andExpect(status().isNotFound());

        // Anonymous callers never read a video.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/social/v1/videos/" + videoId))
                .andExpect(status().isUnauthorized());

        // Once the author deletes the post, a second independent reader who
        // could play it before loses access with the same 404.
        Actor secondReader = newUser("Second Video Reader");
        getAs(secondReader, "/api/social/v1/videos/" + videoId).andExpect(status().isOk());
        deleteAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isNoContent());
        getAs(secondReader, "/api/social/v1/videos/" + videoId).andExpect(status().isNotFound());
    }

    @Test
    void imageAndVideoRoutesAreMutuallyExclusive() throws Exception {
        Actor author = newUser("Mixed Media Author");
        UUID imageId = UUID.fromString(json(uploadImage(author)
                .andExpect(status().isCreated()).andReturn()).get("imageId").asText());

        getAs(author, "/api/social/v1/videos/" + imageId).andExpect(status().isNotFound());
    }

    @Test
    void videoPostNeedsACaptionAndCannotCarryAnImage() throws Exception {
        Actor author = newUser("Captionless Author");
        String videoId = uploadVideoId(author).toString();
        UUID imageId = UUID.fromString(json(uploadImage(author)
                .andExpect(status().isCreated()).andReturn()).get("imageId").asText());

        postAs(author, "/api/v1/feed/posts", java.util.Map.of(
                "videoId", videoId, "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        postAs(author, "/api/v1/feed/posts", java.util.Map.of(
                "body", "both kinds", "videoId", videoId, "imageId", imageId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void videoPostRejectsAnotherUsersUploadAndTypeConfusion() throws Exception {
        Actor author = newUser("Video Owner");
        Actor other = newUser("Video Borrower");
        String videoId = uploadVideoId(author).toString();
        UUID imageId = UUID.fromString(json(uploadImage(other)
                .andExpect(status().isCreated()).andReturn()).get("imageId").asText());

        // Another user's video cannot be attached.
        postAs(other, "/api/v1/feed/posts", java.util.Map.of(
                "body", "not mine", "videoId", videoId,
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // A still image is not a videoId, even when it is the caller's own.
        postAs(other, "/api/v1/feed/posts", java.util.Map.of(
                "body", "type confusion", "videoId", imageId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // And a video is not an imageId.
        String ownVideoId = uploadVideoId(other).toString();
        postAs(other, "/api/v1/feed/posts", java.util.Map.of(
                "body", "type confusion", "imageId", ownVideoId,
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void videoUploadRejectsAudioOnlyBrandOnlyAndOversizedFiles() throws Exception {
        Actor author = newUser("Bad Video Uploader");

        byte[] audioOnly;
        try (java.io.InputStream in = FeedFlowIT.class.getResourceAsStream("/fixtures/voice_aac_isom_faststart.m4a")) {
            audioOnly = in.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not read voice fixture", failure);
        }
        mockMvc.perform(multipart("/api/social/v1/videos")
                        .file(new MockMultipartFile("file", "voice.mp4", "video/mp4", audioOnly))
                        .header("Authorization", bearer(author)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("MIME_NOT_ALLOWED"));

        // A bare ftyp brand with no moov box at all is not a video.
        byte[] brandOnly = hex("000000186674797069736F6D0000020069736F6D617631");
        mockMvc.perform(multipart("/api/social/v1/videos")
                        .file(new MockMultipartFile("file", "brand.mp4", "video/mp4", brandOnly))
                        .header("Authorization", bearer(author)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("MIME_NOT_ALLOWED"));

        // One byte over the 4 MiB video ceiling, rejected before sniffing.
        byte[] oversized = new byte[(int) (FeedImageService.MAX_VIDEO_BYTES + 1)];
        mockMvc.perform(multipart("/api/social/v1/videos")
                        .file(new MockMultipartFile("file", "huge.mp4", "video/mp4", oversized))
                        .header("Authorization", bearer(author)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));

        // Rejections never burned the upload quota: a valid video still passes.
        uploadVideo(author).andExpect(status().isCreated());
    }

    // ------------------------------------------- hardening & new social APIs

    @Test
    void deletingOrEditingCommentOnBlockedOrDeletedParentPostIsNotFound() throws Exception {
        Actor postAuthor = newUser("Parent Host");
        Actor commenter = newUser("Thread Commenter");
        Actor blockedReader = newUser("Blocked Comment Deleter");

        UUID blockedPostId = createPostId(postAuthor, "post before block");
        UUID commentOnBlockedPost = UUID.fromString(json(postAs(commenter,
                "/api/v1/feed/posts/" + blockedPostId + "/comments",
                Map.of("body", "visible comment", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());
        postAs(postAuthor, "/api/v1/users/" + blockedReader.id() + "/block", null)
                .andExpect(status().isNoContent());

        // Parent post is invisible to blockedReader: must be 404, never 403.
        deleteAs(blockedReader, "/api/v1/feed/comments/" + commentOnBlockedPost)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));

        // Deleting the parent post makes the comment 404 for both others and its author.
        UUID doomedPostId = createPostId(postAuthor, "post to delete");
        UUID commentOnDeletedPost = UUID.fromString(json(postAs(commenter,
                "/api/v1/feed/posts/" + doomedPostId + "/comments",
                Map.of("body", "orphaned", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());
        deleteAs(postAuthor, "/api/v1/feed/posts/" + doomedPostId).andExpect(status().isNoContent());

        deleteAs(blockedReader, "/api/v1/feed/comments/" + commentOnDeletedPost)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        deleteAs(commenter, "/api/v1/feed/comments/" + commentOnDeletedPost)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        patchAs(commenter, "/api/v1/feed/comments/" + commentOnDeletedPost, Map.of("body", "edit"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));

        // Soft-deleting the post author also hides comments from delete/edit probes.
        Actor doomedAuthor = newUser("Soon Deleted Post Author");
        UUID authoredPostId = createPostId(doomedAuthor, "vanishing author post");
        UUID commentOnDeletedAuthorPost = UUID.fromString(json(postAs(commenter,
                "/api/v1/feed/posts/" + authoredPostId + "/comments",
                Map.of("body", "still here?", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", doomedAuthor.id());

        deleteAs(blockedReader, "/api/v1/feed/comments/" + commentOnDeletedAuthorPost)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
    }

    @Test
    void ownerCannotReadBoundMediaAfterPostIsDeleted() throws Exception {
        Actor author = newUser("Bound Media Owner");

        UUID imageId = UUID.fromString(json(uploadImage(author)
                .andExpect(status().isCreated())).get("imageId").asText());
        UUID videoId = uploadVideoId(author);
        String imageKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM public_images WHERE id = ?", String.class, imageId);
        String videoKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM public_images WHERE id = ?", String.class, videoId);

        // Unbound assets stay readable by the owner.
        getAs(author, "/api/v1/feed/images/" + imageId).andExpect(status().isOk());
        getAs(author, "/api/social/v1/videos/" + videoId).andExpect(status().isOk());

        UUID imagePostId = UUID.fromString(json(postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "photo", "imageId", imageId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());
        UUID videoPostId = UUID.fromString(json(postAs(author, "/api/social/v1/posts", Map.of(
                "body", "clip", "videoId", videoId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());

        deleteAs(author, "/api/v1/feed/posts/" + imagePostId).andExpect(status().isNoContent());
        deleteAs(author, "/api/v1/feed/posts/" + videoPostId).andExpect(status().isNoContent());

        // Bound media of a deleted post is 404 even to the owner across all endpoints.
        getAs(author, "/api/v1/feed/images/" + imageId).andExpect(status().isNotFound());
        getAs(author, "/api/social/v1/images/" + imageId).andExpect(status().isNotFound());
        getAs(author, "/api/v1/files/" + imageKey).andExpect(status().isNotFound());
        getAs(author, "/api/v1/feed/videos/" + videoId).andExpect(status().isNotFound());
        getAs(author, "/api/social/v1/videos/" + videoId).andExpect(status().isNotFound());
        getAs(author, "/api/v1/files/" + videoKey).andExpect(status().isNotFound());
    }

    @Test
    void deadPostReplayDoesNotBurnQuotaAndBoundVideoReportsVideoId() throws Exception {
        Actor author = newUser("Dead Post Replay Author");
        UUID imageId = UUID.fromString(json(uploadImage(author)
                .andExpect(status().isCreated())).get("imageId").asText());
        UUID imageClientPostId = UUID.randomUUID();
        Map<String, String> imagePayload = Map.of(
                "body", "deleted image post",
                "imageId", imageId.toString(),
                "clientPostId", imageClientPostId.toString());
        UUID imagePostId = UUID.fromString(json(postAs(author, "/api/v1/feed/posts", imagePayload)
                .andExpect(status().isCreated())).get("id").asText());
        deleteAs(author, "/api/v1/feed/posts/" + imagePostId).andExpect(status().isNoContent());

        // Replaying the deleted image post returns 404 (not "already attached") and burns 0 quota.
        for (int i = 0; i < 4; i++) {
            postAs(author, "/api/v1/feed/posts", imagePayload)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
        }

        // Reusing an already-bound videoId with a fresh clientPostId says videoId, not imageId.
        UUID videoId = uploadVideoId(author);
        postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "first video", "videoId", videoId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
        postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "second video", "videoId", videoId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("videoId is already attached to a post"));

        // Exactly 2 posts were created; the 4 dead replays and 1 validation rejection burned 0 tokens.
        String bucket = "feed:post:minute:" + author.id();
        int remaining = 0;
        while (rateLimitService.tryConsume(bucket, FeedService.POSTS_PER_MINUTE,
                Duration.ofMinutes(1)).allowed()) {
            remaining++;
        }
        assertThat(remaining).isEqualTo(FeedService.POSTS_PER_MINUTE - 2);
    }

    @Test
    void deadOrCrossPostCommentReplayDoesNotBurnQuotaOrLeak() throws Exception {
        Actor author = newUser("Comment Dead Replay Author");
        UUID post1 = createPostId(author, "post one");
        UUID post2 = createPostId(author, "post two");

        UUID deletedClientCommentId = UUID.randomUUID();
        Map<String, String> deletedPayload = Map.of(
                "body", "ephemeral", "clientCommentId", deletedClientCommentId.toString());
        UUID deletedCommentId = UUID.fromString(json(postAs(author,
                "/api/v1/feed/posts/" + post1 + "/comments", deletedPayload)
                .andExpect(status().isCreated())).get("id").asText());
        deleteAs(author, "/api/v1/feed/comments/" + deletedCommentId).andExpect(status().isNoContent());

        for (int i = 0; i < 11; i++) {
            postAs(author, "/api/v1/feed/posts/" + post1 + "/comments", deletedPayload)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        }

        UUID liveClientCommentId = UUID.randomUUID();
        postAs(author, "/api/v1/feed/posts/" + post1 + "/comments", Map.of(
                "body", "post1 comment", "clientCommentId", liveClientCommentId.toString()))
                .andExpect(status().isCreated());

        // Reusing post1's clientCommentId on post2 must not return post1's comment or burn quota.
        postAs(author, "/api/v1/feed/posts/" + post2 + "/comments", Map.of(
                "body", "cross post", "clientCommentId", liveClientCommentId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));

        String bucket = "feed:comment:minute:" + author.id();
        int remaining = 0;
        while (rateLimitService.tryConsume(bucket, FeedService.COMMENTS_PER_MINUTE,
                Duration.ofMinutes(1)).allowed()) {
            remaining++;
        }
        assertThat(remaining).isEqualTo(FeedService.COMMENTS_PER_MINUTE - 2);
    }

    @Test
    void duplicateAndMissingFilePartsAreRejectedOnFeedUploads() throws Exception {
        Actor author = newUser("Multipart Feed Author");

        for (String path : List.of("/api/v1/feed/images", "/api/social/v1/images")) {
            mockMvc.perform(multipart(path)
                            .file(new MockMultipartFile("file", "a.png", "image/png", PNG_BYTES))
                            .file(new MockMultipartFile("file", "b.png", "image/png", PNG_BYTES))
                            .header("Authorization", bearer(author)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
            mockMvc.perform(multipart(path).header("Authorization", bearer(author)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        byte[] mp4 = videoBytes();
        for (String path : List.of("/api/v1/feed/videos", "/api/social/v1/videos")) {
            mockMvc.perform(multipart(path)
                            .file(new MockMultipartFile("file", "a.mp4", "video/mp4", mp4))
                            .file(new MockMultipartFile("file", "b.mp4", "video/mp4", mp4))
                            .header("Authorization", bearer(author)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
            mockMvc.perform(multipart(path).header("Authorization", bearer(author)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void videoEndpointSupportsHttpRangeAndHeadRequests() throws Exception {
        Actor author = newUser("Range Video Author");
        Actor reader = newUser("Range Video Reader");
        UUID videoId = uploadVideoId(author);
        UUID postId = UUID.fromString(json(postAs(author, "/api/social/v1/posts", Map.of(
                "body", "range clip", "videoId", videoId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());
        byte[] all = videoBytes();

        for (String path : List.of(
                "/api/v1/feed/videos/" + videoId,
                "/api/social/v1/videos/" + videoId)) {
            mockMvc.perform(get(path).header("Authorization", bearer(reader)))
                    .andExpect(status().isOk())
                    .andExpect(result -> {
                        assertThat(result.getResponse().getHeader("Accept-Ranges")).isEqualTo("bytes");
                        assertThat(result.getResponse().getContentLength()).isEqualTo(all.length);
                        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(all);
                    });

            mockMvc.perform(head(path).header("Authorization", bearer(reader)))
                    .andExpect(status().isOk())
                    .andExpect(result -> {
                        assertThat(result.getResponse().getHeader("Accept-Ranges")).isEqualTo("bytes");
                        assertThat(result.getResponse().getContentLength()).isEqualTo(all.length);
                    });

            mockMvc.perform(get(path)
                            .header("Authorization", bearer(reader))
                            .header("Range", "bytes=0-63"))
                    .andExpect(status().isPartialContent())
                    .andExpect(result -> {
                        assertThat(result.getResponse().getContentType()).isEqualTo("video/mp4");
                        assertThat(result.getResponse().getHeader("Accept-Ranges")).isEqualTo("bytes");
                        assertThat(result.getResponse().getHeader("Content-Range"))
                                .isEqualTo("bytes 0-63/" + all.length);
                        assertThat(result.getResponse().getContentLength()).isEqualTo(64);
                        assertThat(result.getResponse().getHeader("Cache-Control"))
                                .contains("no-store");
                        assertThat(result.getResponse().getHeader("X-Content-Type-Options"))
                                .isEqualTo("nosniff");
                        assertThat(result.getResponse().getContentAsByteArray())
                                .isEqualTo(Arrays.copyOfRange(all, 0, 64));
                    });

            mockMvc.perform(get(path)
                            .header("Authorization", bearer(reader))
                            .header("Range", "bytes=-32"))
                    .andExpect(status().isPartialContent())
                    .andExpect(result -> {
                        int start = all.length - 32;
                        assertThat(result.getResponse().getHeader("Content-Range"))
                                .isEqualTo("bytes " + start + "-" + (all.length - 1) + "/" + all.length);
                        assertThat(result.getResponse().getContentLength()).isEqualTo(32);
                        assertThat(result.getResponse().getContentAsByteArray())
                                .isEqualTo(Arrays.copyOfRange(all, start, all.length));
                    });

            for (String badRange : List.of(
                    "bytes=" + all.length + "-",
                    "bytes=-0",
                    "bytes=20-10",
                    "bytes=abc-def")) {
                mockMvc.perform(get(path)
                                .header("Authorization", bearer(reader))
                                .header("Range", badRange))
                        .andExpect(status().isRequestedRangeNotSatisfiable())
                        .andExpect(jsonPath("$.error.code").value("RANGE_NOT_SATISFIABLE"))
                        .andExpect(result -> {
                            assertThat(result.getResponse().getHeader("Accept-Ranges"))
                                    .isEqualTo("bytes");
                            assertThat(result.getResponse().getHeader("Content-Range"))
                                    .isEqualTo("bytes */" + all.length);
                        });
            }
        }

        // Deleted post's video still answers 404 (not 416) even with a Range header.
        deleteAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/social/v1/videos/" + videoId)
                        .header("Authorization", bearer(reader))
                        .header("Range", "bytes=999999-"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void editingPostsAndCommentsEnforcesOwnershipVisibilityValidationAndEditedAt() throws Exception {
        Actor author = newUser("Edit Author");
        Actor other = newUser("Edit Bystander");

        JsonNode createdPost = createPost(author, "initial post");
        assertThat(createdPost.get("editedAt").isNull()).isTrue();
        UUID postId = UUID.fromString(createdPost.get("id").asText());

        // Blank & over-limit rejected.
        patchAs(author, "/api/v1/feed/posts/" + postId, Map.of("body", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        patchAs(author, "/api/v1/feed/posts/" + postId, Map.of("body", "x".repeat(1001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        // Non-author gets 403 on visible post.
        patchAs(other, "/api/v1/feed/posts/" + postId, Map.of("body", "hijack"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // Valid edit via legacy and canonical alias stamps editedAt and updates body.
        JsonNode editedPost = json(patchAs(author, "/api/social/v1/posts/" + postId,
                Map.of("body", "  edited post body  "))
                .andExpect(status().isOk()));
        assertThat(editedPost.get("body").asText()).isEqualTo("edited post body");
        assertThat(editedPost.get("editedAt").asText()).isNotBlank();
        assertThat(editedPost.get("createdAt").asText())
                .isEqualTo(createdPost.get("createdAt").asText());

        JsonNode fetchedPost = json(getAs(other, "/api/v1/feed/posts/" + postId)
                .andExpect(status().isOk()));
        assertThat(fetchedPost.get("body").asText()).isEqualTo("edited post body");
        assertThat(fetchedPost.get("editedAt").asText())
                .isEqualTo(editedPost.get("editedAt").asText());

        // Comment edit lifecycle.
        JsonNode createdComment = json(postAs(other,
                "/api/v1/feed/posts/" + postId + "/comments",
                Map.of("body", "initial comment", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()));
        assertThat(createdComment.get("editedAt").isNull()).isTrue();
        UUID commentId = UUID.fromString(createdComment.get("id").asText());

        patchAs(other, "/api/v1/feed/comments/" + commentId, Map.of("body", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        patchAs(other, "/api/v1/feed/comments/" + commentId, Map.of("body", "y".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        patchAs(author, "/api/v1/feed/comments/" + commentId, Map.of("body", "not mine"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        JsonNode editedComment = json(patchAs(other, "/api/v1/feed/comments/" + commentId,
                Map.of("body", "  edited comment  "))
                .andExpect(status().isOk()));
        assertThat(editedComment.get("body").asText()).isEqualTo("edited comment");
        assertThat(editedComment.get("editedAt").asText()).isNotBlank();

        JsonNode editedCommentCanonical = json(patchAs(other, "/api/social/v1/comments/" + commentId,
                Map.of("body", "edited again"))
                .andExpect(status().isOk()));
        assertThat(editedCommentCanonical.get("body").asText()).isEqualTo("edited again");

        // Block hides parent post -> comment edit returns 404 COMMENT_NOT_FOUND, post edit 404.
        postAs(author, "/api/v1/users/" + other.id() + "/block", null)
                .andExpect(status().isNoContent());
        patchAs(other, "/api/v1/feed/comments/" + commentId, Map.of("body", "after block"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        patchAs(other, "/api/v1/feed/posts/" + postId, Map.of("body", "after block"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));

        // Deleting post returns 404 on subsequent edit.
        deleteAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isNoContent());
        patchAs(author, "/api/v1/feed/posts/" + postId, Map.of("body", "after delete"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
    }

    @Test
    void authorProfileCountsLivePostsAndRespectsBlocksAndDeletes() throws Exception {
        Actor author = newUser("Profile Author");
        Actor viewer = newUser("Profile Viewer");
        createPostId(author, "live one");
        createPostId(author, "live two");
        UUID deletedPostId = createPostId(author, "deleted three");
        deleteAs(author, "/api/v1/feed/posts/" + deletedPostId).andExpect(status().isNoContent());

        for (String path : List.of(
                "/api/v1/feed/authors/" + author.id(),
                "/api/social/v1/authors/" + author.id())) {
            JsonNode profile = json(getAs(viewer, path).andExpect(status().isOk()));
            assertThat(profile.get("userId").asText()).isEqualTo(author.id().toString());
            assertThat(profile.get("displayName").asText()).isEqualTo("Profile Author");
            assertThat(profile.get("postCount").asLong()).isEqualTo(2L);
        }

        getAs(viewer, "/api/v1/feed/authors/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

        // Bidirectional block: author blocks viewer -> 404 USER_NOT_FOUND.
        postAs(author, "/api/v1/users/" + viewer.id() + "/block", null)
                .andExpect(status().isNoContent());
        getAs(viewer, "/api/social/v1/authors/" + author.id())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

        // Deleted user -> 404 USER_NOT_FOUND.
        Actor deletedUser = newUser("Deleted Profile");
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", deletedUser.id());
        getAs(viewer, "/api/v1/feed/authors/" + deletedUser.id())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    void feedSupportsAuthorFilterAndAccentInsensitiveKeywordSearch() throws Exception {
        Actor alice = newUser("Search Alice");
        Actor bob = newUser("Search Bob");
        Actor blocked = newUser("Search Blocked");
        Actor viewer = newUser("Search Viewer");

        UUID imageId = UUID.fromString(json(uploadImage(alice)
                .andExpect(status().isCreated())).get("imageId").asText());
        UUID aliceImageOnly = UUID.fromString(json(postAs(alice, "/api/v1/feed/posts", Map.of(
                "imageId", imageId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());
        UUID aliceCoffee = createPostId(alice, "Cà phê sáng ở Hà Nội");
        UUID aliceLiteral = createPostId(alice, "100% real_test\\path here");
        UUID bobCoffee = createPostId(bob, "Cà phê chiều Sài Gòn");
        createPostId(bob, "1000 false_positive path");
        createPostId(blocked, "Cà phê blocked");

        postAs(viewer, "/api/v1/users/" + blocked.id() + "/block", null)
                .andExpect(status().isNoContent());

        // Author filter isolates Alice's posts (including image-only).
        assertThat(ids(feed(viewer, "?authorId=" + alice.id())))
                .containsExactlyInAnyOrder(
                        aliceImageOnly.toString(), aliceCoffee.toString(), aliceLiteral.toString());

        // Blocked or unknown authorId returns an empty page (no 404 existence leak).
        for (UUID hiddenAuthor : List.of(blocked.id(), UUID.randomUUID())) {
            JsonNode emptyPage = feed(viewer, "?authorId=" + hiddenAuthor);
            assertThat(emptyPage.get("items")).isEmpty();
            assertThat(emptyPage.get("nextCursor").isNull()).isTrue();
        }

        // Accent- & case-insensitive search matches Alice and Bob, excludes blocked & image-only.
        JsonNode coffeeResults = json(mockMvc.perform(get("/api/social/v1/posts")
                        .queryParam("q", "ca phe")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()));
        assertThat(ids(coffeeResults))
                .containsExactlyInAnyOrder(aliceCoffee.toString(), bobCoffee.toString());

        // Combined authorId + q + keyset pagination.
        JsonNode combined = json(mockMvc.perform(get("/api/v1/feed/posts")
                        .queryParam("authorId", alice.id().toString())
                        .queryParam("q", "ha noi")
                        .queryParam("limit", "1")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()));
        assertThat(ids(combined)).containsExactly(aliceCoffee.toString());
        assertThat(combined.get("nextCursor").isNull()).isTrue();

        // LIKE wildcards (%, _, \) are escaped literally.
        JsonNode percentMatch = json(mockMvc.perform(get("/api/v1/feed/posts")
                        .queryParam("q", "100%")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()));
        assertThat(ids(percentMatch)).containsExactly(aliceLiteral.toString());
        JsonNode underscoreMatch = json(mockMvc.perform(get("/api/v1/feed/posts")
                        .queryParam("q", "_test\\path")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()));
        assertThat(ids(underscoreMatch)).containsExactly(aliceLiteral.toString());

        // Blank q behaves as unfiltered; >100 chars returns 400 VALIDATION_FAILED.
        JsonNode blankQuery = json(mockMvc.perform(get("/api/v1/feed/posts")
                        .queryParam("authorId", bob.id().toString())
                        .queryParam("q", "   ")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()));
        assertThat(ids(blankQuery)).hasSize(2);
        mockMvc.perform(get("/api/v1/feed/posts")
                        .queryParam("q", "a".repeat(101))
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void nulBytesInPostCommentSearchAndReportReturnValidationFailed() throws Exception {
        Actor author = newUser("Nul Author");
        UUID postId = createPostId(author, "clean post");
        UUID commentId = UUID.fromString(json(postAs(author,
                "/api/v1/feed/posts/" + postId + "/comments",
                Map.of("body", "clean comment", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())).get("id").asText());

        postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "bad\u0000post", "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        patchAs(author, "/api/v1/feed/posts/" + postId, Map.of("body", "bad\u0000edit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        postAs(author, "/api/v1/feed/posts/" + postId + "/comments", Map.of(
                "body", "bad\u0000comment", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        patchAs(author, "/api/v1/feed/comments/" + commentId, Map.of("body", "bad\u0000cedit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/feed/posts")
                        .queryParam("q", "bad\u0000search")
                        .header("Authorization", bearer(author)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        postAs(author, "/api/v1/reports", Map.of(
                "targetType", "POST",
                "targetId", postId.toString(),
                "reason", "SPAM",
                "details", "bad\u0000details"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

}
