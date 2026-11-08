package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;
import com.nexachat.core.common.AppConfig;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class AttachmentDto {

    public static final String MIME_PREFIX_IMAGE = "image/";
    public static final String MIME_PREFIX_VIDEO = "video/";
    public static final String MIME_PREFIX_AUDIO = "audio/";

    @Nullable private final String id;
    @Nullable private final String storageKey;
    @Nullable private final String originalName;
    @Nullable private final String mimeType;
    @Nullable private final Long sizeBytes;
    @Nullable private final Integer width;
    @Nullable private final Integer height;
    @Nullable private final Long durationMs;
    @Nullable private final String url;
    @Nullable private final String blurHash;

    public AttachmentDto() {
        this(null, null, null, null, null, null, null, null, null, null);
    }

    public AttachmentDto(@Nullable String id, @Nullable String storageKey, @Nullable String originalName, @Nullable String mimeType, @Nullable Long sizeBytes, @Nullable Integer width, @Nullable Integer height, @Nullable Long durationMs, @Nullable String url) {
        this(id, storageKey, originalName, mimeType, sizeBytes, width, height, durationMs, url, null);
    }

    public AttachmentDto(
            @Nullable String id,
            @Nullable String storageKey,
            @Nullable String originalName,
            @Nullable String mimeType,
            @Nullable Long sizeBytes,
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable Long durationMs,
            @Nullable String url,
            @Nullable String blurHash) {
        this.id = id;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.durationMs = durationMs;
        this.url = url;
        this.blurHash = blurHash;
    }

    public AttachmentDto(String id, String originalName, String mimeType) {
        this(id, null, originalName, mimeType, null, null, null, null, null);
    }

    public AttachmentDto(String id, String originalName, String mimeType, Long sizeBytes) {
        this(id, null, originalName, mimeType, sizeBytes, null, null, null, null);
    }

    @Nullable public String getId() {
        return id;
    }

    @Nullable public String getStorageKey() {
        return storageKey;
    }

    @Nullable public String getOriginalName() {
        return originalName;
    }

    @Nullable public String getMimeType() {
        return mimeType;
    }

    @Nullable public Long getSizeBytes() {
        return sizeBytes;
    }

    @Nullable public Integer getWidth() {
        return width;
    }

    @Nullable public Integer getHeight() {
        return height;
    }

    @Nullable public Long getDurationMs() {
        return durationMs;
    }

    @Nullable public String getUrl() {
        return url;
    }

    @Nullable public String getBlurHash() {
        return blurHash;
    }


    @Nullable
    public String contentUrl() {
        if (url == null || JsonText.isBlank(url)) {
            return id == null ? null : contentPath(id);
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("/")) {
            return AppConfig.API_BASE_URL + url;
        }
        return AppConfig.API_BASE_URL + "/" + url;
    }

    public boolean isImage() {
        return mimeType != null && mimeType.startsWith(MIME_PREFIX_IMAGE);
    }

    public boolean isVideo() {
        return mimeType != null && mimeType.startsWith(MIME_PREFIX_VIDEO);
    }

    public boolean isAudio() {
        return mimeType != null && mimeType.startsWith(MIME_PREFIX_AUDIO);
    }

    public AttachmentDto withMimeType(String mimeType) {
        return new AttachmentDto(
                id, storageKey, originalName, mimeType, sizeBytes, width, height, durationMs, url,
                blurHash);
    }

    public AttachmentDto withBlurHash(@Nullable String blurHash) {
        return new AttachmentDto(
                id, storageKey, originalName, mimeType, sizeBytes, width, height, durationMs, url,
                blurHash);
    }

    /** Returns the Room-safe representation; placeholder hashes remain process-local only. */
    public AttachmentDto withoutBlurHash() {
        return blurHash == null
                ? this
                : new AttachmentDto(
                        id, storageKey, originalName, mimeType, sizeBytes, width, height,
                        durationMs, url, null);
    }

    @NonNull
    public String getMessageType() {
        return messageTypeForMime(mimeType);
    }

    @NonNull
    public static String contentPath(@NonNull String attachmentId) {
        return AppConfig.API_BASE_URL + "/api/v1/attachments/" + attachmentId + "/content";
    }

    @NonNull
    public static String messageTypeForMime(@Nullable String mimeType) {
        if (mimeType != null && mimeType.startsWith(MIME_PREFIX_IMAGE)) {
            return MessageDto.TYPE_IMAGE;
        }
        if (mimeType != null && mimeType.startsWith(MIME_PREFIX_VIDEO)) {
            return MessageDto.TYPE_VIDEO;
        }
        if (mimeType != null && mimeType.startsWith(MIME_PREFIX_AUDIO)) {
            return MessageDto.TYPE_AUDIO;
        }
        return MessageDto.TYPE_FILE;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AttachmentDto that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(storageKey, that.storageKey)
                && Objects.equals(originalName, that.originalName)
                && Objects.equals(mimeType, that.mimeType)
                && Objects.equals(sizeBytes, that.sizeBytes)
                && Objects.equals(width, that.width)
                && Objects.equals(height, that.height)
                && Objects.equals(durationMs, that.durationMs)
                && Objects.equals(url, that.url)
                && Objects.equals(blurHash, that.blurHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                id, storageKey, originalName, mimeType, sizeBytes, width, height, durationMs, url,
                blurHash);
    }
}
