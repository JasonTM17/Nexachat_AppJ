package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ConversationDto {
    private final String id;
    @Nullable private final String type;
    @Nullable private final String title;
    @Nullable private final String avatarUrl;
    @Nullable private final List<ConversationMemberDto> members;
    @Nullable private final String createdAt;
    /** Caller's own per-conversation AI opt-out state. */
    @Nullable private final Boolean aiOptOut;
    /** Server flag: true once E2EE is enabled on this DIRECT conversation. */
    @Nullable private final Boolean e2eeEnabled;

    public ConversationDto() {
        this(null, null, null, null, List.of(), null, null);
    }

    public ConversationDto(@NonNull String id, @Nullable String type, @Nullable String title, @Nullable String avatarUrl, @Nullable List<ConversationMemberDto> members, @Nullable String createdAt, @Nullable Boolean aiOptOut) {
        this(id, type, title, avatarUrl, members, createdAt, aiOptOut, null);
    }

    public ConversationDto(@NonNull String id, @Nullable String type, @Nullable String title, @Nullable String avatarUrl, @Nullable List<ConversationMemberDto> members, @Nullable String createdAt, @Nullable Boolean aiOptOut, @Nullable Boolean e2eeEnabled) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.avatarUrl = avatarUrl;
        this.members = members == null ? List.of() : members;
        this.createdAt = createdAt;
        this.aiOptOut = aiOptOut;
        this.e2eeEnabled = e2eeEnabled;
    }

    public ConversationDto(@NonNull String id, @Nullable String type, @Nullable String title, @Nullable String avatarUrl, @Nullable List<ConversationMemberDto> members, @Nullable String createdAt) {
        this(id, type, title, avatarUrl, members, createdAt, null);
    }

    public ConversationDto(String id, String type) {
        this(id, type, null, null, List.of(), null);
    }

    @NonNull public String getId() {
        return id;
    }

    @Nullable public String getType() {
        return type;
    }

    @Nullable public String getTitle() {
        return title;
    }

    @Nullable public String getAvatarUrl() {
        return avatarUrl;
    }

    @NonNull public List<ConversationMemberDto> getMembers() {
        return members;
    }

    @Nullable public String getCreatedAt() {
        return createdAt;
    }

    /** Caller's own AI opt-out state; absent in legacy payloads. */
    @Nullable public Boolean getAiOptOut() {
        return aiOptOut;
    }

    /** Server E2EE flag; absent in legacy payloads. */
    @Nullable public Boolean getE2eeEnabled() {
        return e2eeEnabled;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ConversationDto that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(type, that.type)
                && Objects.equals(title, that.title)
                && Objects.equals(avatarUrl, that.avatarUrl)
                && Objects.equals(members, that.members)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(aiOptOut, that.aiOptOut);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, title, avatarUrl, members, createdAt, aiOptOut);
    }
}
