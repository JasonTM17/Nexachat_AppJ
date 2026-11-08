package com.nexachat.feature.ask;

import androidx.annotation.Nullable;

/** Message citation returned with an answer. */
public final class AskCitation {
    @Nullable private final String messageId;
    @Nullable private final Long seq;
    @Nullable private final String snippet;

    AskCitation() {
        this(null, null, null);
    }

    public AskCitation(@Nullable String messageId, @Nullable Long seq, @Nullable String snippet) {
        this.messageId = messageId;
        this.seq = seq;
        this.snippet = snippet;
    }

    @Nullable
    public String getMessageId() {
        return messageId;
    }

    @Nullable
    public Long getSeq() {
        return seq;
    }

    @Nullable
    public String getSnippet() {
        return snippet;
    }
}
