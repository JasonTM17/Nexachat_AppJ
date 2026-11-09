package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class EditMessageRequest {
    private final String content;
    @Nullable private final List<MessageMention> mentions;
    @Nullable private final Boolean mentionAll;

    public EditMessageRequest() {
        this(null, null, null);
    }

    public EditMessageRequest(@NonNull String content) {
        this(content, null, null);
    }

    public EditMessageRequest(@NonNull String content,
            @Nullable List<MessageMention> mentions, boolean mentionAll) {
        this(content, mentions, Boolean.valueOf(mentionAll));
    }

    public EditMessageRequest(@Nullable String content,
            @Nullable List<MessageMention> mentions, @Nullable Boolean mentionAll) {
        this.content = content;
        this.mentions = mentions == null ? null : List.copyOf(mentions);
        this.mentionAll = mentionAll;
    }

    @NonNull public String getContent() {
        return content;
    }

    @Nullable public List<MessageMention> getMentions() { return mentions; }
    @Nullable public Boolean getMentionAll() { return mentionAll; }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof EditMessageRequest that)) {
            return false;
        }
        return Objects.equals(content, that.content)
                && Objects.equals(mentions, that.mentions)
                && Objects.equals(mentionAll, that.mentionAll);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, mentions, mentionAll);
    }
}
