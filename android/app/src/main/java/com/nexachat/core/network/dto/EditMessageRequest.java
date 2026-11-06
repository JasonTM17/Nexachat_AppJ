package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class EditMessageRequest {
    private final String content;

    public EditMessageRequest() {
        this(null);
    }

    public EditMessageRequest(@NonNull String content) {
        this.content = content;
    }

    @NonNull public String getContent() {
        return content;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof EditMessageRequest that)) {
            return false;
        }
        return Objects.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content);
    }
}
