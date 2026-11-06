package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class DirectConversationRequest {
    @Nullable private final String userId;
    @Nullable private final String phone;

    public DirectConversationRequest() {
        this(null, null);
    }

    public DirectConversationRequest(@Nullable String userId, @Nullable String phone) {
        this.userId = userId;
        this.phone = phone;
    }

    @Nullable public String getUserId() {
        return userId;
    }

    @Nullable public String getPhone() {
        return phone;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DirectConversationRequest that)) {
            return false;
        }
        return Objects.equals(userId, that.userId)
                && Objects.equals(phone, that.phone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, phone);
    }
}
