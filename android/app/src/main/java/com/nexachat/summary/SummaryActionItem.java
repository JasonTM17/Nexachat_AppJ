package com.nexachat.summary;

import java.util.List;

/** A cited action item with optional owner and due hint. */
public final class SummaryActionItem {
    private final String text;
    private final String ownerUserId;
    private final String dueHint;
    private final List<String> messageIds;

    SummaryActionItem() {
        this(null, null, null, null);
    }

    public SummaryActionItem(
            String text,
            String ownerUserId,
            String dueHint,
            List<String> messageIds) {
        this.text = text == null ? "" : text;
        this.ownerUserId = ownerUserId;
        this.dueHint = dueHint;
        this.messageIds = SummaryLists.immutableCopy(messageIds);
    }

    public String getText() {
        return text;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public String getDueHint() {
        return dueHint;
    }

    public List<String> getMessageIds() {
        return SummaryLists.immutableCopy(messageIds);
    }
}
