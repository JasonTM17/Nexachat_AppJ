package com.nexachat.feature.inbox;

/** Inbox header filter chip visibility rules. */
public final class InboxFilterChipRules {

    private InboxFilterChipRules() {}

    public static boolean showLabelFilterRow(int customLabelCount) {
        return customLabelCount > 0;
    }
}
