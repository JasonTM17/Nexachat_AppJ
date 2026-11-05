package com.nexachat.summary;

import java.util.List;

/** A cited point, decision, or open question. */
public final class SummaryItem {
    private final String text;
    private final List<String> messageIds;

    SummaryItem() {
        this(null, null);
    }

    public SummaryItem(String text, List<String> messageIds) {
        this.text = text == null ? "" : text;
        this.messageIds = SummaryLists.immutableCopy(messageIds);
    }

    public String getText() {
        return text;
    }

    public List<String> getMessageIds() {
        return SummaryLists.immutableCopy(messageIds);
    }
}
