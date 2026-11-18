package com.nexachat.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.attachment.StorageService;
import com.nexachat.common.error.ApiException;
import com.nexachat.retention.RetentionJobRunner;
import com.nexachat.retention.RetentionProperties;
import com.nexachat.testutil.FeatureItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retention and account-deletion coverage for the public feed: rows that are
 * already invisible are purged once the window passes (with their comments,
 * reactions and stored image), uploads that were never bound are swept, and a
 * deleted account takes its feed content with it.
 */
class FeedRetentionIT extends FeatureItSupport {

    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A"
            + "0000000D4948445200000010000000100806000000");

    @Autowired
    private RetentionJobRunner retentionJobRunner;
    @Autowired
    private RetentionProperties retentionProperties;
    @MockitoSpyBean
    private StorageService storageService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(
                    value.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }

    @AfterEach
    void restoreRetentionDefaults() {
        reset(storageService);
        retentionProperties.setEnabled(false);
        retentionProperties.setDryRun(true);
        retentionProperties.getJobs().getPublicFeed().setEnabled(false);
    }

    private RetentionJobRunner.JobRunResult runFeedRetention() {
        retentionProperties.setEnabled(true);
        retentionProperties.setDryRun(false);
        retentionProperties.getJobs().getPublicFeed().setEnabled(true);
        return retentionJobRunner.runEnabledJobs().get(RetentionJobRunner.Job.PUBLIC_FEED);
    }

    private UUID createPost(Actor author, String body, String imageId) throws Exception {
        Map<String, Object> request = imageId == null
                ? Map.of("body", body, "clientPostId", UUID.randomUUID().toString())
                : Map.of("body", body, "imageId", imageId,
                        "clientPostId", UUID.randomUUID().toString());
        return UUID.fromString(json(postAs(author, "/api/v1/feed/posts", request)
                .andExpect(status().isCreated())).get("id").asText());
    }

    private String uploadImage(Actor owner) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/feed/images")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("imageId").asText();
    }

    private String storageKeyOf(String imageId) {
        return jdbcTemplate.queryForObject(
                "SELECT storage_key FROM public_images WHERE id = ?", String.class,
                UUID.fromString(imageId));
    }

    @Test
    void purgesASoftDeletedPostWithItsCommentsReactionsAndImage() throws Exception {
        Actor author = newUser("Purge Author");
        Actor commenter = newUser("Purge Commenter");
        String imageId = uploadImage(author);
        String storageKey = storageKeyOf(imageId);
        UUID postId = createPost(author, "purge me", imageId);
        postAs(commenter, "/api/v1/feed/posts/" + postId + "/comments",
                Map.of("body", "a comment", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
        putAs(commenter, "/api/v1/feed/posts/" + postId + "/reactions/\uD83D\uDC4D")
                .andExpect(status().isOk());

        deleteAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isNoContent());
        jdbcTemplate.update(
                "UPDATE public_posts SET deleted_at = now() - interval '40 days' WHERE id = ?",
                postId);

        RetentionJobRunner.JobRunResult result = runFeedRetention();

        assertThat(result.errors()).isZero();
        assertThat(result.rowsAffected()).isGreaterThanOrEqualTo(2);
        assertThat(countRows("SELECT count(*) FROM public_posts WHERE id = ?", postId)).isZero();
        assertThat(countRows("SELECT count(*) FROM public_comments WHERE post_id = ?", postId))
                .isZero();
        assertThat(countRows(
                "SELECT count(*) FROM public_post_reactions WHERE post_id = ?", postId)).isZero();
        assertThat(countRows("SELECT count(*) FROM public_images WHERE id = ?",
                UUID.fromString(imageId))).isZero();
        assertThatThrownBy(() -> storageService.open(storageKey))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void purgesAHiddenPostOnceTheWindowPasses() throws Exception {
        Actor author = newUser("Hidden Purge Author");
        Actor moderator = withRole(newUser("Hidden Purge Moderator"), "MODERATOR");
        UUID postId = createPost(author, "hidden and old", null);
        postAs(moderator, "/api/v1/admin/feed/posts/" + postId + "/hide",
                Map.of("reason", "spam"))
                .andExpect(status().isNoContent());
        jdbcTemplate.update(
                "UPDATE public_posts SET hidden_at = now() - interval '40 days' WHERE id = ?",
                postId);

        runFeedRetention();

        assertThat(countRows("SELECT count(*) FROM public_posts WHERE id = ?", postId)).isZero();
    }

    @Test
    void keepsARecentSoftDelete() throws Exception {
        Actor author = newUser("Recent Delete Author");
        UUID postId = createPost(author, "just deleted", null);
        deleteAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isNoContent());

        runFeedRetention();

        assertThat(countRows("SELECT count(*) FROM public_posts WHERE id = ?", postId))
                .isEqualTo(1);
    }

    @Test
    void sweepsAnUnboundImageOlderThanTheWindow() throws Exception {
        Actor author = newUser("Unbound Author");
        String imageId = uploadImage(author);
        String storageKey = storageKeyOf(imageId);
        jdbcTemplate.update(
                "UPDATE public_images SET created_at = now() - interval '25 hours' WHERE id = ?",
                UUID.fromString(imageId));

        RetentionJobRunner.JobRunResult result = runFeedRetention();

        assertThat(result.errors()).isZero();
        assertThat(countRows("SELECT count(*) FROM public_images WHERE id = ?",
                UUID.fromString(imageId))).isZero();
        assertThatThrownBy(() -> storageService.open(storageKey))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void keepsAnUnboundImageInsideTheWindow() throws Exception {
        Actor author = newUser("Fresh Upload Author");
        String imageId = uploadImage(author);

        runFeedRetention();

        assertThat(countRows("SELECT count(*) FROM public_images WHERE id = ?",
                UUID.fromString(imageId))).isEqualTo(1);
    }

    @Test
    void keepsABoundImageWhosePostIsStillVisible() throws Exception {
        Actor author = newUser("Bound Author");
        String imageId = uploadImage(author);
        createPost(author, "still here", imageId);
        jdbcTemplate.update(
                "UPDATE public_images SET created_at = now() - interval '25 hours' WHERE id = ?",
                UUID.fromString(imageId));

        runFeedRetention();

        assertThat(countRows("SELECT count(*) FROM public_images WHERE id = ?",
                UUID.fromString(imageId))).isEqualTo(1);
    }

    @Test
    void sweepsABoundImageNoPostReferencesAnyMore() throws Exception {
        Actor author = newUser("Crash Orphan Author");
        String imageId = uploadImage(author);
        String storageKey = storageKeyOf(imageId);
        UUID postId = createPost(author, "crash victim", imageId);
        // A crash between the post delete and the image delete leaves the
        // image bound but referenced by no post.
        jdbcTemplate.update("DELETE FROM public_posts WHERE id = ?", postId);
        jdbcTemplate.update(
                "UPDATE public_images SET created_at = now() - interval '25 hours' WHERE id = ?",
                UUID.fromString(imageId));

        RetentionJobRunner.JobRunResult result = runFeedRetention();

        assertThat(result.errors()).isZero();
        assertThat(result.filesDeleted()).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM public_images WHERE id = ?",
                UUID.fromString(imageId))).isZero();
        assertThatThrownBy(() -> storageService.open(storageKey))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aFailedObjectDeleteKeepsTheUnboundImageRowForTheNextRun() throws Exception {
        Actor author = newUser("Stubborn Upload Author");
        String imageId = uploadImage(author);
        String storageKey = storageKeyOf(imageId);
        jdbcTemplate.update(
                "UPDATE public_images SET created_at = now() - interval '25 hours' WHERE id = ?",
                UUID.fromString(imageId));
        doThrow(new IllegalStateException("injected storage outage"))
                .when(storageService).deleteIfExists(storageKey);

        RetentionJobRunner.JobRunResult failed = runFeedRetention();

        assertThat(failed.errors()).isEqualTo(1);
        assertThat(failed.filesDeleted()).isZero();
        assertThat(countRows("SELECT count(*) FROM public_images WHERE id = ?",
                UUID.fromString(imageId))).isEqualTo(1);
        assertThat(storageService.exists(storageKey)).isTrue();

        reset(storageService);
        RetentionJobRunner.JobRunResult retried = runFeedRetention();

        assertThat(retried.errors()).isZero();
        assertThat(retried.filesDeleted()).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM public_images WHERE id = ?",
                UUID.fromString(imageId))).isZero();
        assertThatThrownBy(() -> storageService.open(storageKey))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aFailedObjectDeleteKeepsAPurgedPostsImageRowForTheNextRun() throws Exception {
        Actor author = newUser("Stubborn Purge Author");
        String imageId = uploadImage(author);
        String storageKey = storageKeyOf(imageId);
        UUID postId = createPost(author, "purge me", imageId);
        deleteAs(author, "/api/v1/feed/posts/" + postId).andExpect(status().isNoContent());
        jdbcTemplate.update(
                "UPDATE public_posts SET deleted_at = now() - interval '40 days' WHERE id = ?",
                postId);
        jdbcTemplate.update(
                "UPDATE public_images SET created_at = now() - interval '40 days' WHERE id = ?",
                UUID.fromString(imageId));
        doThrow(new IllegalStateException("injected storage outage"))
                .when(storageService).deleteIfExists(storageKey);

        RetentionJobRunner.JobRunResult failed = runFeedRetention();

        // One failed object delete is one error, even though the sweep sees
        // the same now-unreferenced image later in the same run.
        assertThat(failed.errors()).isEqualTo(1);
        assertThat(failed.filesDeleted()).isZero();
        assertThat(countRows("SELECT count(*) FROM public_posts WHERE id = ?", postId)).isZero();
        assertThat(countRows("SELECT count(*) FROM public_images WHERE id = ?",
                UUID.fromString(imageId))).isEqualTo(1);
        assertThat(storageService.exists(storageKey)).isTrue();

        reset(storageService);
        RetentionJobRunner.JobRunResult retried = runFeedRetention();

        assertThat(retried.errors()).isZero();
        assertThat(retried.filesDeleted()).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM public_images WHERE id = ?",
                UUID.fromString(imageId))).isZero();
        assertThatThrownBy(() -> storageService.open(storageKey))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anAlreadyMissingImageObjectStillLetsTheRowGo() throws Exception {
        Actor author = newUser("Vanished Object Author");
        String imageId = uploadImage(author);
        String storageKey = storageKeyOf(imageId);
        jdbcTemplate.update(
                "UPDATE public_images SET created_at = now() - interval '25 hours' WHERE id = ?",
                UUID.fromString(imageId));
        assertThat(storageService.deleteIfExists(storageKey)).isTrue();

        RetentionJobRunner.JobRunResult result = runFeedRetention();

        assertThat(result.errors()).isZero();
        // The object was already absent, so nothing was actually removed.
        assertThat(result.filesDeleted()).isZero();
        assertThat(countRows("SELECT count(*) FROM public_images WHERE id = ?",
                UUID.fromString(imageId))).isZero();
    }

    @Test
    void accountDeletionSoftDeletesFeedContentAndDropsReactions() throws Exception {
        Actor author = newUser("Leaving Author");
        Actor other = newUser("Staying Author");
        UUID ownPost = createPost(author, "my last post", null);
        UUID otherPost = createPost(other, "someone else's post", null);
        postAs(author, "/api/v1/feed/posts/" + otherPost + "/comments",
                Map.of("body", "my comment", "clientCommentId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
        putAs(author, "/api/v1/feed/posts/" + otherPost + "/reactions/\uD83D\uDC4D")
                .andExpect(status().isOk());
        String unboundImageId = uploadImage(author);
        String unboundKey = storageKeyOf(unboundImageId);
        jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE id = ?",
                passwordEncoder.encode("secret"), author.id());

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "secret"))))
                .andExpect(status().isNoContent());

        assertThat(countRows(
                "SELECT count(*) FROM public_posts WHERE id = ? AND deleted_at IS NOT NULL",
                ownPost)).isEqualTo(1);
        assertThat(countRows("SELECT count(*) FROM public_images WHERE id = ?",
                UUID.fromString(unboundImageId))).isZero();
        assertThatThrownBy(() -> storageService.open(unboundKey))
                .isInstanceOf(ApiException.class);
        assertThat(countRows("""
                SELECT count(*) FROM public_comments
                WHERE author_id = ? AND deleted_at IS NOT NULL
                """, author.id())).isEqualTo(1);
        assertThat(countRows(
                "SELECT count(*) FROM public_post_reactions WHERE user_id = ?", author.id()))
                .isZero();

        JsonNode feed = json(getAs(other, "/api/v1/feed/posts").andExpect(status().isOk()));
        assertThat(feed.get("items").toString()).doesNotContain(ownPost.toString());
    }
}
