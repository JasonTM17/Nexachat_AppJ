package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Detail projection for {@code group/{id}/detail}. Members are fetch-only and
 * come from inbox payloads rather than Room; the title is a fallback while a
 * Room row is not available yet.
 */
public final class GroupDetail {

    @Nullable private final String title;
    private final List<GroupMember> members;

    public GroupDetail(@Nullable String title, List<GroupMember> members) {
        this.title = title;
        this.members = Collections.unmodifiableList(new ArrayList<>(members));
    }

    @Nullable public String getTitle() { return title; }
    public List<GroupMember> getMembers() { return members; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GroupDetail that)) return false;
        return Objects.equals(title, that.title) && members.equals(that.members);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, members);
    }
}
