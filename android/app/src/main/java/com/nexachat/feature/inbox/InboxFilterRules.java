package com.nexachat.feature.inbox;

import androidx.annotation.NonNull;
import com.nexachat.data.ConversationSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure rules for filtering the already-loaded inbox rows. */
public final class InboxFilterRules {

    private InboxFilterRules() {}

    @NonNull
    public static List<ConversationSummary> apply(
            @NonNull List<ConversationSummary> conversations,
            @NonNull InboxFilter filter) {
        Objects.requireNonNull(conversations, "conversations");
        Objects.requireNonNull(filter, "filter");

        List<ConversationSummary> filtered = new ArrayList<>();
        for (ConversationSummary conversation : conversations) {
            boolean include;
            if (filter == InboxFilter.ARCHIVED) {
                include = conversation.isArchived();
            } else if (conversation.isArchived()) {
                // The default inbox hides archived rows; the Archived chip is
                // the only place they appear.
                include = false;
            } else if (filter == InboxFilter.UNREAD) {
                include = conversation.getUnreadCount() > 0;
            } else if (filter == InboxFilter.GROUPS) {
                include = conversation.isGroup();
            } else {
                include = true;
            }
            if (include) {
                filtered.add(conversation);
            }
        }
        return List.copyOf(filtered);
    }
}
