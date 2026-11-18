package com.nexachat.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.testutil.FeatureItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
}