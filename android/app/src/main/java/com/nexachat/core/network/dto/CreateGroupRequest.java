package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class CreateGroupRequest {
    private final String title;
    private final List<String> memberIds;

    public CreateGroupRequest() {
        this(null, List.of());
    }

    public CreateGroupRequest(@NonNull String title, @NonNull List<String> memberIds) {
        this.title = title;
        this.memberIds = memberIds == null ? List.of() : memberIds;
    }

    @NonNull public String getTitle() {
        return title;
    }

    @NonNull public List<String> getMemberIds() {
        return memberIds;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CreateGroupRequest that)) {
            return false;
        }
        return Objects.equals(title, that.title)
                && Objects.equals(memberIds, that.memberIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, memberIds);
    }
}
