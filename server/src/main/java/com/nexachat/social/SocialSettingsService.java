package com.nexachat.social;

import com.nexachat.admin.AdminAuditService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime feature switches for the web Social surface plus the one bilingual
 * live announcement, backed by the singleton {@code social_settings} row.
 *
 * <p>There is deliberately no cache: a flag flip must take effect on the next
 * request, and the row is a single indexed read. {@code web_enabled} gates
 * only the {@code /api/social/v1} alias through {@link SocialFeatureInterceptor};
 * the legacy {@code /api/v1/feed} routes used by Android never consult it.
 * {@code video_uploads_enabled} gates new video uploads and new video binds
 * on both aliases; existing videos stay readable under the normal feed
 * visibility rules.</p>
 *
 * <p>Every mutation is a conditional {@code UPDATE ... WHERE revision = ?} so
 * concurrent editors cannot overwrite each other, and writes one audit row in
 * the same transaction — a change that cannot be audited does not commit.
 * Audit details carry the flag values and the new revision only, never the
 * announcement copy.</p>
 */
@Service
public class SocialSettingsService {

    static final int MAX_TITLE_LENGTH = 100;
    static final int MAX_BODY_LENGTH = 500;

    /** Bilingual plain-text announcement copy. */
    public record Announcement(String titleVi, String bodyVi, String titleEn, String bodyEn) {
    }

    /** The anonymous bootstrap projection: no revisions, drafts, or identities. */
    public record PublicConfig(boolean webEnabled, boolean videoUploadsEnabled,
                               Announcement announcement) {
    }

    /** The full admin view: concurrency token, effective flags, draft and live copy. */
    public record AdminState(long revision, boolean webEnabled, boolean videoUploadsEnabled,
                             Announcement draft, Announcement published, Instant publishedAt) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final AdminAuditService auditService;

    public SocialSettingsService(JdbcTemplate jdbcTemplate, AdminAuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    /** What the public page may bootstrap with; {@code videoUploadsEnabled} is masked off when the web surface is. */
    @Transactional(readOnly = true)
    public PublicConfig publicConfig() {
        SettingsRow row = requireRow();
        return new PublicConfig(row.webEnabled(),
                row.webEnabled() && row.videoUploadsEnabled(),
                row.published());
    }

    /** Whether the {@code /api/social/v1} alias currently serves requests. */
    public boolean isWebEnabled() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT web_enabled FROM social_settings WHERE id = 1", Boolean.class));
    }

    /** Whether new video uploads/binds are currently admitted on either alias. */
    public boolean isVideoUploadsEnabled() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT video_uploads_enabled FROM social_settings WHERE id = 1", Boolean.class));
    }

    /** Full staff view including the optimistic-concurrency token. */
    @Transactional(readOnly = true)
    public AdminState adminState() {
        return requireRow().toAdminState();
    }

    /**
     * Flips both runtime switches atomically. Both values are always written
     * together so the row never carries a half-applied operator intent.
     */
    @Transactional
    public AdminState updateFlags(long expectedRevision, boolean webEnabled, boolean videoUploadsEnabled,
                                  UUID actorId, User.Role actorRole, String ip) {
        int updated = jdbcTemplate.update("""
                UPDATE social_settings
                SET web_enabled = ?, video_uploads_enabled = ?, revision = revision + 1, updated_at = now()
                WHERE id = 1 AND revision = ?
                """, webEnabled, videoUploadsEnabled, expectedRevision);
        requireUpdated(updated);
        long newRevision = expectedRevision + 1;
        auditService.record(actorId, actorRole, "SOCIAL_FLAGS_UPDATE", "social_settings", "1",
                null, Map.of("webEnabled", webEnabled, "videoUploadsEnabled", videoUploadsEnabled,
                        "revision", newRevision), ip);
        return requireRow().toAdminState();
    }

    /** Saves the editable draft; the live announcement is untouched. */
    @Transactional
    public AdminState saveDraft(long expectedRevision, String titleVi, String bodyVi,
                                String titleEn, String bodyEn,
                                UUID actorId, User.Role actorRole, String ip) {
        Announcement draft = validated(titleVi, bodyVi, titleEn, bodyEn);
        int updated = jdbcTemplate.update("""
                UPDATE social_settings
                SET draft_title_vi = ?, draft_body_vi = ?, draft_title_en = ?, draft_body_en = ?,
                    revision = revision + 1, updated_at = now()
                WHERE id = 1 AND revision = ?
                """, draft.titleVi(), draft.bodyVi(), draft.titleEn(), draft.bodyEn(), expectedRevision);
        requireUpdated(updated);
        long newRevision = expectedRevision + 1;
        auditService.record(actorId, actorRole, "SOCIAL_CMS_DRAFT_SAVE", "social_settings", "1",
                null, Map.of("revision", newRevision), ip);
        return requireRow().toAdminState();
    }

    /** Copies the current draft onto the live announcement in one conditional update. */
    @Transactional
    public AdminState publish(long expectedRevision, UUID actorId, User.Role actorRole, String ip) {
        int updated = jdbcTemplate.update("""
                UPDATE social_settings
                SET published_title_vi = draft_title_vi, published_body_vi = draft_body_vi,
                    published_title_en = draft_title_en, published_body_en = draft_body_en,
                    published_at = now(), revision = revision + 1, updated_at = now()
                WHERE id = 1 AND revision = ? AND draft_title_vi IS NOT NULL
                """, expectedRevision);
        if (updated == 0) {
            SettingsRow current = requireRow();
            if (current.revision() != expectedRevision) {
                throw conflict();
            }
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "No draft to publish");
        }
        long newRevision = expectedRevision + 1;
        auditService.record(actorId, actorRole, "SOCIAL_CMS_PUBLISH", "social_settings", "1",
                null, Map.of("revision", newRevision), ip);
        return requireRow().toAdminState();
    }

    /** Takes the live announcement down. Always succeeds when the revision matches — takedown must never be blocked. */
    @Transactional
    public AdminState unpublish(long expectedRevision, UUID actorId, User.Role actorRole, String ip) {
        int updated = jdbcTemplate.update("""
                UPDATE social_settings
                SET published_title_vi = NULL, published_body_vi = NULL,
                    published_title_en = NULL, published_body_en = NULL, published_at = NULL,
                    revision = revision + 1, updated_at = now()
                WHERE id = 1 AND revision = ?
                """, expectedRevision);
        requireUpdated(updated);
        long newRevision = expectedRevision + 1;
        auditService.record(actorId, actorRole, "SOCIAL_CMS_UNPUBLISH", "social_settings", "1",
                null, Map.of("revision", newRevision), ip);
        return requireRow().toAdminState();
    }

    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw conflict();
        }
    }

    private static ApiException conflict() {
        return ApiException.of(ApiErrorCode.SOCIAL_SETTINGS_CONFLICT,
                "Settings changed since they were loaded; reload and retry");
    }

    private static Announcement validated(String titleVi, String bodyVi, String titleEn, String bodyEn) {
        return new Announcement(
                validatedText(titleVi, MAX_TITLE_LENGTH, "titleVi"),
                validatedText(bodyVi, MAX_BODY_LENGTH, "bodyVi"),
                validatedText(titleEn, MAX_TITLE_LENGTH, "titleEn"),
                validatedText(bodyEn, MAX_BODY_LENGTH, "bodyEn"));
    }

    private static String validatedText(String value, int maxLength, String field) {
        if (value == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, field + " is required");
        }
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.length() > maxLength) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    field + " must be 1-" + maxLength + " characters");
        }
        return stripped;
    }

    private SettingsRow requireRow() {
        return jdbcTemplate.queryForObject("""
                SELECT revision, web_enabled, video_uploads_enabled,
                       draft_title_vi, draft_body_vi, draft_title_en, draft_body_en,
                       published_title_vi, published_body_vi, published_title_en, published_body_en,
                       published_at
                FROM social_settings WHERE id = 1
                """, (rs, rowNum) -> mapRow(rs));
    }

    private static SettingsRow mapRow(ResultSet rs) throws SQLException {
        return new SettingsRow(
                rs.getLong("revision"),
                rs.getBoolean("web_enabled"),
                rs.getBoolean("video_uploads_enabled"),
                announcementOrNull(rs, "draft"),
                announcementOrNull(rs, "published"),
                rs.getTimestamp("published_at") == null ? null
                        : rs.getTimestamp("published_at").toInstant());
    }

    private static Announcement announcementOrNull(ResultSet rs, String prefix) throws SQLException {
        String titleVi = rs.getString(prefix + "_title_vi");
        if (titleVi == null) {
            return null;
        }
        return new Announcement(titleVi, rs.getString(prefix + "_body_vi"),
                rs.getString(prefix + "_title_en"), rs.getString(prefix + "_body_en"));
    }

    private record SettingsRow(long revision, boolean webEnabled, boolean videoUploadsEnabled,
                               Announcement draft, Announcement published, Instant publishedAt) {
        private AdminState toAdminState() {
            return new AdminState(revision, webEnabled, videoUploadsEnabled, draft, published, publishedAt);
        }
    }
}
