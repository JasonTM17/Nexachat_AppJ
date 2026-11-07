package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/** Group title and members resolved from the inbox payload. */
public record GroupDetail(@Nullable String title, List<GroupMember> members) {
    public GroupDetail {
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }
}
