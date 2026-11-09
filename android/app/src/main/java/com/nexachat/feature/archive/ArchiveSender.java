package com.nexachat.feature.archive;

import androidx.annotation.Nullable;
import java.util.Objects;

/** A conversation member available to the sender filter. */
public final class ArchiveSender {

    private final String id;
    @Nullable private final String displayName;

    public ArchiveSender(String id, @Nullable String displayName) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = displayName;
    }

    public String getId() { return id; }
    @Nullable public String getDisplayName() { return displayName; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ArchiveSender that)) return false;
        return id.equals(that.id) && Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName);
    }
}
