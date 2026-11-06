package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class AddGroupMembersRequest {
    private final List<String> userIds;

    public AddGroupMembersRequest() {
        this(List.of());
    }

    public AddGroupMembersRequest(@NonNull List<String> userIds) {
        this.userIds = userIds == null ? List.of() : userIds;
    }

    @NonNull public List<String> getUserIds() {
        return userIds;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AddGroupMembersRequest that)) {
            return false;
        }
        return Objects.equals(userIds, that.userIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userIds);
    }
}
