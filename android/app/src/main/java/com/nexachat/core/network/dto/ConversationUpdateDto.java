package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Payload of {@code CONVERSATION_UPDATED}: a full membership snapshot taken
 * after the change. A member absent from {@link #getMembers()} is no longer in
 * the conversation, which is how a device learns that it was removed.
 */
public final class ConversationUpdateDto {
    @Nullable private final String conversationId;
    @Nullable private final String title;
    @Nullable private final String avatarUrl;
    @Nullable private final String change;
    @Nullable private final List<ConversationMemberDto> members;
    @Nullable private final Boolean e2eeEnabled;
    /** Timer in seconds (0 = off); null when the server omits it (leave local state). */
    @Nullable private Integer disappearingSeconds;

    public ConversationUpdateDto() {
        this(null, null, null, null, List.of());
    }

    public ConversationUpdateDto(
            @Nullable String conversationId,
            @Nullable String title,
            @Nullable String avatarUrl,
            @Nullable String change,
            @Nullable List<ConversationMemberDto> members) {
        this(conversationId, title, avatarUrl, change, members, null);
    }

    public ConversationUpdateDto(
            @Nullable String conversationId,
            @Nullable String title,
            @Nullable String avatarUrl,
            @Nullable String change,
            @Nullable List<ConversationMemberDto> members,
            @Nullable Boolean e2eeEnabled) {
        this.conversationId = conversationId;
        this.title = title;
        this.avatarUrl = avatarUrl;
        this.change = change;
        this.members = members == null ? List.of() : members;
        this.e2eeEnabled = e2eeEnabled;
    }

    @Nullable public String getConversationId() { return conversationId; }
    @Nullable public String getTitle() { return title; }
    @Nullable public String getAvatarUrl() { return avatarUrl; }
    @Nullable public String getChange() { return change; }
    @Nullable public Boolean getE2eeEnabled() { return e2eeEnabled; }
    @Nullable public Integer getDisappearingSeconds() { return disappearingSeconds; }
    public void setDisappearingSeconds(@Nullable Integer disappearingSeconds) {
        this.disappearingSeconds = disappearingSeconds;
    }
    @NonNull public List<ConversationMemberDto> getMembers() {
        return members == null ? List.of() : members;
    }

    /** Member ids only; the removal check never needs the other profile fields. */
    @NonNull
    public List<String> getMemberIds() {
        List<String> ids = new ArrayList<>(getMembers().size());
        for (ConversationMemberDto member : getMembers()) {
            if (member.getUserId() != null) ids.add(member.getUserId());
        }
        return Collections.unmodifiableList(ids);
    }
}
