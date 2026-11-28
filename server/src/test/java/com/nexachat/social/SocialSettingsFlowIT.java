package com.nexachat.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexachat.testutil.FeatureItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runtime Social feature controls and the live announcement CMS: the
 * anonymous bootstrap, both enforcement points, the ADMIN-only control
 * surface, optimistic-concurrency conflicts, draft/publish isolation, and
 * audit atomicity. The singleton row is reset to both-switches-off before
 * every test by {@link FeatureItSupport}.
 */
class SocialSettingsFlowIT extends FeatureItSupport {

    @MockitoSpyBean
    private com.nexachat.admin.AdminAuditService auditSpy;

    private int auditRows(String action) {
        return countRows(
                "SELECT count(*) FROM admin_audit_log WHERE action = ? AND target_type = 'social_settings'",
                action);
    }

    private Actor admin(String name) {
        return withRole(newUser(name), "ADMIN");
    }

    private JsonNode publicConfig() throws Exception {
        return json(mockMvc.perform(get("/api/social/v1/config"))
                .andExpect(status().isOk()));
    }

    private void patchFlags(Actor admin, long revision, boolean web, boolean video) throws Exception {
        patchAs(admin, "/api/v1/admin/social/settings/flags", Map.of(
                "expectedRevision", revision, "webEnabled", web, "videoUploadsEnabled", video))
                .andExpect(status().isOk());
    }

    private long currentRevision(Actor admin) throws Exception {
        return json(getAs(admin, "/api/v1/admin/social/settings")
                .andExpect(status().isOk())).get("revision").asLong();
    }

    // ------------------------------------------------------------ bootstrap

    @Test
    void configIsAnonymousNoStoreAndDefaultsOff() throws Exception {
        mockMvc.perform(get("/api/social/v1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webEnabled").value(false))
                .andExpect(jsonPath("$.videoUploadsEnabled").value(false))
                .andExpect(jsonPath("$.announcement").doesNotExist())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Cache-Control"))
                        .contains("no-store"));
    }

    // ---------------------------------------------------------- enforcement

    @Test
    void webAliasRequiresTheFlagWhileLegacyFeedIsUnaffected() throws Exception {
        Actor user = newUser("Switch User");
        Actor admin = admin("Switch Admin");

        getAs(user, "/api/social/v1/posts")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SOCIAL_FEATURE_DISABLED"));
        // The Android surface never consults the web kill switch.
        getAs(user, "/api/v1/feed/posts").andExpect(status().isOk());
        // The public bootstrap stays readable while everything else is off.
        mockMvc.perform(get("/api/social/v1/config")).andExpect(status().isOk());

        patchFlags(admin, currentRevision(admin), true, false);

        getAs(user, "/api/social/v1/posts").andExpect(status().isOk());
    }

    @Test
    void videoUploadsAndBindsAreGatedOnBothAliases() throws Exception {
        Actor author = newUser("Video Gate Author");
        Actor admin = admin("Video Gate Admin");
        byte[] mp4 = videoBytes();
        long rev = currentRevision(admin);
        patchFlags(admin, rev, true, false);

        // Web on, video off: uploads are refused on both aliases.
        mockMvc.perform(multipart("/api/social/v1/videos")
                        .file(new MockMultipartFile("file", "clip.mp4", "video/mp4", mp4))
                        .header("Authorization", bearer(author)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SOCIAL_FEATURE_DISABLED"));
        mockMvc.perform(multipart("/api/v1/feed/videos")
                        .file(new MockMultipartFile("file", "clip.mp4", "video/mp4", mp4))
                        .header("Authorization", bearer(author)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SOCIAL_FEATURE_DISABLED"));
        // Text and image posts are unaffected by the video switch.
        postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "still fine", "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());

        patchFlags(admin, currentRevision(admin), true, true);
        UUID videoId = UUID.fromString(json(mockMvc.perform(multipart("/api/social/v1/videos")
                        .file(new MockMultipartFile("file", "clip.mp4", "video/mp4", mp4))
                        .header("Authorization", bearer(author)))
                .andExpect(status().isCreated())).get("videoId").asText());

        // Turning video back off blocks new binds but never reads of what exists.
        patchFlags(admin, currentRevision(admin), true, false);
        postAs(author, "/api/v1/feed/posts", Map.of(
                "body", "bind attempt", "videoId", videoId.toString(),
                "clientPostId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SOCIAL_FEATURE_DISABLED"));
        getAs(author, "/api/v1/feed/videos/" + videoId).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ admin authz

    @Test
    void settingsRoutesRequireTheAdminRole() throws Exception {
        Actor plain = newUser("Settings Plain");
        Actor moderator = withRole(newUser("Settings Moderator"), "MODERATOR");
        Actor admin = admin("Settings Admin");

        mockMvc.perform(get("/api/v1/admin/social/settings"))
                .andExpect(status().isUnauthorized());
        getAs(plain, "/api/v1/admin/social/settings").andExpect(status().isForbidden());
        getAs(moderator, "/api/v1/admin/social/settings").andExpect(status().isForbidden());
        getAs(admin, "/api/v1/admin/social/settings").andExpect(status().isOk());

        // Mutations are ADMIN-only too: a moderator passing the staff filter
        // chain is still stopped by the in-guard role check, with no side effects.
        long revBefore = currentRevision(admin);
        patchAs(moderator, "/api/v1/admin/social/settings/flags", Map.of(
                "expectedRevision", revBefore,
                "webEnabled", true,
                "videoUploadsEnabled", true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ADMIN"));
        assertThat(currentRevision(admin)).isEqualTo(revBefore);
    }

    @Test
    void configIsNotMappedOnTheLegacyFeedAlias() throws Exception {
        // The bootstrap is single-mapped: the legacy Android alias must never
        // answer it, so there is no second public config surface to keep in sync.
        Actor user = newUser("Config Negative");
        getAs(user, "/api/v1/feed/config").andExpect(status().isNotFound());
    }

    // --------------------------------------------------------- flags + audit

    @Test
    void flagUpdatesAreAuditedAndConflictSafe() throws Exception {
        Actor admin = admin("Flags Admin");
        long rev = currentRevision(admin);
        assertThat(rev).isEqualTo(1);

        patchFlags(admin, rev, true, true);
        assertThat(auditRows("SOCIAL_FLAGS_UPDATE")).isEqualTo(1);
        assertThat(currentRevision(admin)).isEqualTo(2);
        assertThat(publicConfig().get("webEnabled").asBoolean()).isTrue();
        assertThat(publicConfig().get("videoUploadsEnabled").asBoolean()).isTrue();

        // A stale expected revision changes nothing and writes no audit row.
        patchAs(admin, "/api/v1/admin/social/settings/flags", Map.of(
                "expectedRevision", rev, "webEnabled", false, "videoUploadsEnabled", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOCIAL_SETTINGS_CONFLICT"));
        assertThat(auditRows("SOCIAL_FLAGS_UPDATE")).isEqualTo(1);
        assertThat(publicConfig().get("webEnabled").asBoolean()).isTrue();
    }

    @Test
    void failedAuditRollsBackTheFlagChange() throws Exception {
        Actor admin = admin("Audit Admin");
        long rev = currentRevision(admin);

        org.mockito.Mockito.doThrow(new IllegalStateException("audit down"))
                .when(auditSpy).record(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("SOCIAL_FLAGS_UPDATE"),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        patchAs(admin, "/api/v1/admin/social/settings/flags", Map.of(
                "expectedRevision", rev, "webEnabled", true, "videoUploadsEnabled", true))
                .andExpect(status().is5xxServerError());
        assertThat(auditRows("SOCIAL_FLAGS_UPDATE")).isZero();
        assertThat(publicConfig().get("webEnabled").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ CMS

    @Test
    void draftPublishUnpublishCycleKeepsLiveCopyIsolated() throws Exception {
        Actor admin = admin("Cms Admin");
        Map<String, Object> draft = Map.of(
                "expectedRevision", currentRevision(admin),
                "titleVi", "Bảo trì định kỳ", "bodyVi", "NexaChat bảo trì lúc 2 giờ sáng.",
                "titleEn", "Scheduled maintenance", "bodyEn", "NexaChat is offline at 2 AM.");

        // Saving a draft never changes what the public page shows.
        putAs(admin, "/api/v1/admin/social/settings/announcement/draft", draft)
                .andExpect(status().isOk());
        assertThat(publicConfig().hasNonNull("announcement")).isFalse();
        JsonNode state = json(getAs(admin, "/api/v1/admin/social/settings")
                .andExpect(status().isOk()));
        assertThat(state.get("draft").get("titleVi").asText()).isEqualTo("Bảo trì định kỳ");
        assertThat(auditRows("SOCIAL_CMS_DRAFT_SAVE")).isEqualTo(1);

        // Publishing the draft snapshot makes exactly those strings public.
        postAs(admin, "/api/v1/admin/social/settings/announcement/publish",
                Map.of("expectedRevision", currentRevision(admin)))
                .andExpect(status().isOk());
        JsonNode announcement = publicConfig().get("announcement");
        assertThat(announcement.get("titleVi").asText()).isEqualTo("Bảo trì định kỳ");
        assertThat(announcement.get("bodyEn").asText()).isEqualTo("NexaChat is offline at 2 AM.");
        assertThat(auditRows("SOCIAL_CMS_PUBLISH")).isEqualTo(1);

        // Editing the draft again leaves the live copy untouched.
        putAs(admin, "/api/v1/admin/social/settings/announcement/draft", Map.of(
                "expectedRevision", currentRevision(admin),
                "titleVi", "Nháp mới", "bodyVi", "Nội dung nháp.",
                "titleEn", "New draft", "bodyEn", "Draft body."))
                .andExpect(status().isOk());
        assertThat(publicConfig().get("announcement").get("titleVi").asText())
                .isEqualTo("Bảo trì định kỳ");

        // Unpublish takes the live copy down and keeps the draft.
        postAs(admin, "/api/v1/admin/social/settings/announcement/unpublish",
                Map.of("expectedRevision", currentRevision(admin)))
                .andExpect(status().isOk());
        JsonNode config = publicConfig();
        assertThat(config.hasNonNull("announcement")).isFalse();
        assertThat(json(getAs(admin, "/api/v1/admin/social/settings")
                .andExpect(status().isOk())).get("draft").get("titleVi").asText())
                .isEqualTo("Nháp mới");
        assertThat(auditRows("SOCIAL_CMS_UNPUBLISH")).isEqualTo(1);
    }

    @Test
    void invalidDraftsAndPublishingWithoutADraftAreRejected() throws Exception {
        Actor admin = admin("Validation Admin");

        putAs(admin, "/api/v1/admin/social/settings/announcement/draft", Map.of(
                "expectedRevision", currentRevision(admin),
                "titleVi", "   ", "bodyVi", "body",
                "titleEn", "title", "bodyEn", "body"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        putAs(admin, "/api/v1/admin/social/settings/announcement/draft", Map.of(
                "expectedRevision", currentRevision(admin),
                "titleVi", "title", "bodyVi", "x".repeat(501),
                "titleEn", "title", "bodyEn", "body"))
                .andExpect(status().isBadRequest());

        postAs(admin, "/api/v1/admin/social/settings/announcement/publish",
                Map.of("expectedRevision", currentRevision(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(auditRows("SOCIAL_CMS_PUBLISH")).isZero();

        putAs(admin, "/api/v1/admin/social/settings/announcement/draft", Map.of(
                "expectedRevision", currentRevision(admin),
                "titleVi", "tiêu\u0000đề", "bodyVi", "body",
                "titleEn", "title", "bodyEn", "body"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // --------------------------------------------------------------- helper

    private static byte[] videoBytes() {
        try (java.io.InputStream in = SocialSettingsFlowIT.class.getResourceAsStream(
                "/fixtures/feed_video_h264_64x48.mp4")) {
            if (in == null) {
                throw new IllegalStateException("feed video fixture missing");
            }
            return in.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not read feed video fixture", failure);
        }
    }
}
