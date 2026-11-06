package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class ReactionSummaryDto {
    @Nullable private final String emoji;
    @Nullable private final Integer count;
    @Nullable private final Boolean reactedByMe;
    @Nullable private final Boolean byMe;
    @Nullable private final List<String> userIds;

    public ReactionSummaryDto() {
        this(null, null, null, null, List.of());
    }

    public ReactionSummaryDto(@Nullable String emoji, @Nullable Integer count, @Nullable Boolean reactedByMe, @Nullable Boolean byMe, @Nullable List<String> userIds) {
        this.emoji = emoji;
        this.count = count;
        this.reactedByMe = reactedByMe;
        this.byMe = byMe;
        this.userIds = userIds == null ? List.of() : userIds;
    }

    public ReactionSummaryDto(String emoji, Integer count) {
        this(emoji, count, null, null, List.of());
    }

    public ReactionSummaryDto(String emoji, Integer count, Boolean reactedByMe) {
        this(emoji, count, reactedByMe, null, List.of());
    }

    public ReactionSummaryDto(String emoji, Integer count, java.util.List<String> userIds) {
        this(emoji, count, null, null, userIds);
    }

    @Nullable public String getEmoji() {
        return emoji;
    }

    @Nullable public Integer getCount() {
        return count;
    }

    @Nullable public Boolean getReactedByMe() {
        return reactedByMe;
    }

    @Nullable public Boolean getByMe() {
        return byMe;
    }

    @NonNull public List<String> getUserIds() {
        return userIds;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ReactionSummaryDto that)) {
            return false;
        }
        return Objects.equals(emoji, that.emoji)
                && Objects.equals(count, that.count)
                && Objects.equals(reactedByMe, that.reactedByMe)
                && Objects.equals(byMe, that.byMe)
                && Objects.equals(userIds, that.userIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(emoji, count, reactedByMe, byMe, userIds);
    }
}
