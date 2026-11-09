package com.nexachat.feature.label;

import androidx.annotation.NonNull;
import java.util.Objects;

/** Caller-owned conversation label shown in the inbox and chat actions. */
public final class ConversationLabel {
    private final String id;
    private final String name;
    private final LabelColor color;
    private final boolean pending;

    public ConversationLabel(
            @NonNull String id,
            @NonNull String name,
            @NonNull LabelColor color,
            boolean pending) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.color = Objects.requireNonNull(color, "color");
        this.pending = pending;
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getName() { return name; }
    @NonNull public LabelColor getColor() { return color; }
    public boolean isPending() { return pending; }

    @NonNull
    public ConversationLabel withNameAndColor(@NonNull String newName, @NonNull LabelColor newColor) {
        return new ConversationLabel(id, newName, newColor, pending);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ConversationLabel that)) return false;
        return pending == that.pending && id.equals(that.id) && name.equals(that.name)
                && color == that.color;
    }

    @Override public int hashCode() {
        return Objects.hash(id, name, color, pending);
    }
}
