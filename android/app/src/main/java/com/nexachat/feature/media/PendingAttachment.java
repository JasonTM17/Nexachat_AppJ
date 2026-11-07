package com.nexachat.feature.media;

import android.net.Uri;
import androidx.annotation.Nullable;
import java.util.Objects;

/** A picked file staged above the composer while its upload runs. */
public final class PendingAttachment {

    public enum PendingState {
        UPLOADING,
        FAILED
    }

    private final String localId;
    private final Uri uri;
    @Nullable private final String displayName;
    @Nullable private final String mimeType;
    private final String type;
    private final float progress;
    private final PendingState state;

    public PendingAttachment(
            String localId,
            Uri uri,
            @Nullable String displayName,
            @Nullable String mimeType,
            String type,
            float progress,
            PendingState state) {
        this.localId = Objects.requireNonNull(localId, "localId");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.type = Objects.requireNonNull(type, "type");
        this.progress = progress;
        this.state = Objects.requireNonNull(state, "state");
    }

    public String getLocalId() {
        return localId;
    }

    public Uri getUri() {
        return uri;
    }

    @Nullable
    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public String getMimeType() {
        return mimeType;
    }

    public String getType() {
        return type;
    }

    public float getProgress() {
        return progress;
    }

    public PendingState getState() {
        return state;
    }

    public PendingAttachment withProgress(float nextProgress) {
        return new PendingAttachment(localId, uri, displayName, mimeType, type, nextProgress, state);
    }

    public PendingAttachment withState(PendingState nextState) {
        return new PendingAttachment(localId, uri, displayName, mimeType, type, progress, nextState);
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
}
