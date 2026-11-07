package com.nexachat.feature.media;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * A picked item staged above the input bar while its upload runs (phase-09 send
 * pipeline). Rows are keyed by {@link #getLocalId()}; the server attachment id
 * only exists after upload completion, when the row leaves the strip and the
 * optimistic outbox bubble takes over.
 */
public final class PendingAttachment {

    @NonNull private final String localId;
    @NonNull private final Uri uri;
    @Nullable private final String displayName;
    @Nullable private final String mimeType;
    /** Derived wire type (IMAGE/VIDEO/AUDIO/FILE). */
    @NonNull private final String type;
    private final float progress;
    @NonNull private final PendingState state;

    public PendingAttachment(
            @NonNull String localId,
            @NonNull Uri uri,
            @Nullable String displayName,
            @Nullable String mimeType,
            @NonNull String type,
            float progress,
            @NonNull PendingState state) {
        this.localId = Objects.requireNonNull(localId, "localId");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.type = Objects.requireNonNull(type, "type");
        this.progress = progress;
        this.state = Objects.requireNonNull(state, "state");
    }

    @NonNull public String getLocalId() { return localId; }
    @NonNull public Uri getUri() { return uri; }
    @Nullable public String getDisplayName() { return displayName; }
    @Nullable public String getMimeType() { return mimeType; }
    @NonNull public String getType() { return type; }
    public float getProgress() { return progress; }
    @NonNull public PendingState getState() { return state; }

    public PendingAttachment withProgress(float value) {
        return new PendingAttachment(localId, uri, displayName, mimeType, type, value, state);
    }

    public PendingAttachment withState(@NonNull PendingState value) {
        return new PendingAttachment(localId, uri, displayName, mimeType, type, progress, value);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PendingAttachment that)) {
            return false;
        }
        return Float.compare(progress, that.progress) == 0
                && localId.equals(that.localId)
                && uri.equals(that.uri)
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(mimeType, that.mimeType)
                && type.equals(that.type)
                && state == that.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(localId, uri, displayName, mimeType, type, progress, state);
    }

    public enum PendingState { UPLOADING, FAILED }

    @NonNull
    public static String formatBytes(@Nullable Long bytes) {
        if (bytes == null || bytes < 0L) {
            return "";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
