package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class UpdateConversationRequest {
    @Nullable private final String title;
    @Nullable private final String avatarUrl;

    public UpdateConversationRequest() {
        this(null, null);
    }

    public UpdateConversationRequest(@Nullable String title, @Nullable String avatarUrl) {
        this.title = title;
        this.avatarUrl = avatarUrl;
    }

    public UpdateConversationRequest(String title) {
        this(title, null);
    }

    @Nullable public String getTitle() {
        return title;
    }

    @Nullable public String getAvatarUrl() {
        return avatarUrl;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UpdateConversationRequest that)) {
            return false;
        }
        return Objects.equals(title, that.title)
                && Objects.equals(avatarUrl, that.avatarUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, avatarUrl);
    }
}
