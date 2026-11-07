package com.nexachat.data;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Local metadata sniffed from a picked URI before upload — enough for the
 * preview strip and send pipeline without re-reading the resolver twice.
 */
public final class LocalAttachment {

    @NonNull private final Uri uri;
    @Nullable private final String displayName;
    @Nullable private final String mimeType;
    private final long sizeBytes;
    @NonNull private final String type;

    public LocalAttachment(
            @NonNull Uri uri,
            @Nullable String displayName,
            @Nullable String mimeType,
            long sizeBytes,
            @NonNull String type) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.type = Objects.requireNonNull(type, "type");
    }

    @NonNull
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

    /** Best-effort size; -1 when the provider does not report one. */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /** Wire {@code type} derived from the MIME prefix (IMAGE/VIDEO/AUDIO/FILE). */
    @NonNull
    public String getType() {
        return type;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof LocalAttachment that)) {
            return false;
        }
        return sizeBytes == that.sizeBytes
                && uri.equals(that.uri)
                && Objects.equals(displayName, that.displayName)
                && Objects.equals(mimeType, that.mimeType)
                && type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, displayName, mimeType, sizeBytes, type);
    }
}
