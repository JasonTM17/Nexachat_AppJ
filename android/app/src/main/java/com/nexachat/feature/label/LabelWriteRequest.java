package com.nexachat.feature.label;

import androidx.annotation.NonNull;
import java.util.Objects;

/** Body for label create and partial update requests. */
public final class LabelWriteRequest {
    private final String name;
    private final String colorId;

    public LabelWriteRequest(@NonNull String name, @NonNull String colorId) {
        this.name = Objects.requireNonNull(name, "name");
        this.colorId = Objects.requireNonNull(colorId, "colorId");
    }

    @NonNull public String getName() { return name; }
    @NonNull public String getColorId() { return colorId; }
}
