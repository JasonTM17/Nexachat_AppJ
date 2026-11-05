package com.nexachat.summary;

/** Participant count included in a generated summary. */
public final class SummaryParticipant {
    private final String userId;
    private final String displayName;
    private final int messageCount;

    SummaryParticipant() {
        this(null, null, 0);
    }

    public SummaryParticipant(String userId, String displayName, int messageCount) {
        this.userId = userId;
        this.displayName = displayName;
        this.messageCount = messageCount;
    }

    public String getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getMessageCount() {
        return messageCount;
    }
}
