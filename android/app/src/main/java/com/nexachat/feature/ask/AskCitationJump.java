package com.nexachat.feature.ask;

import androidx.annotation.Nullable;

/** Citation target passed to the chat's existing history-jump flow. */
public final class AskCitationJump {
    private final String messageId;
    @Nullable private final Long seq;

    private AskCitationJump(String messageId, @Nullable Long seq) {
        this.messageId = messageId;
        this.seq = seq;
    }

    @Nullable
    public static AskCitationJump from(@Nullable AskCitation citation) {
        if (citation == null || citation.getMessageId() == null
                || citation.getMessageId().trim().isEmpty()) {
            return null;
        }
        return new AskCitationJump(citation.getMessageId(), citation.getSeq());
    }

    public String getMessageId() {
        return messageId;
    }

    @Nullable
    public Long getSeq() {
        return seq;
    }
}
