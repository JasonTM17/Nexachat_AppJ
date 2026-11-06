package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class DirectConversationResponse {
    private final String id;
    @Nullable private final String type;
    @Nullable private final List<ConversationMemberDto> members;
    @Nullable private final String createdAt;

    public DirectConversationResponse() {
        this(null, null, List.of(), null);
    }

    public DirectConversationResponse(@NonNull String id, @Nullable String type, @Nullable List<ConversationMemberDto> members, @Nullable String createdAt) {
        this.id = id;
        this.type = type;
        this.members = members == null ? List.of() : members;
        this.createdAt = createdAt;
    }

    @NonNull public String getId() {
        return id;
    }

    @Nullable public String getType() {
        return type;
    }

    @NonNull public List<ConversationMemberDto> getMembers() {
        return members;
    }

    @Nullable public String getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DirectConversationResponse that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(type, that.type)
                && Objects.equals(members, that.members)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, members, createdAt);
    }
}
