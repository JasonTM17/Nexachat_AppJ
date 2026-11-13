package com.nexachat.feature.inbox;

import androidx.annotation.NonNull;
import com.nexachat.data.ConversationSummary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure rules for the inbox Active Now story rail. */
public final class ActiveNowPeerRules {

    public static final int MAX_VISIBLE_PEERS = 8;

    private ActiveNowPeerRules() {}

    public static int captionMaxLines(float fontScale) {
        return fontScale >= 1.3f ? 2 : 1;
    }

    @NonNull
    public static List<ConversationSummary> selectOnlineDirectPeers(
            @NonNull List<ConversationSummary> rows) {
        List<ConversationSummary> peers = new ArrayList<>();
        Set<String> seenPeerKeys = new HashSet<>();
        for (ConversationSummary row : rows) {
            if (row.isGroup()) {
                continue;
            }
            if (!Boolean.TRUE.equals(InboxBadgeRules.resolveOnlineStatus(row))) {
                continue;
            }
            String peerKey = peerIdentityKey(row);
            if (!seenPeerKeys.add(peerKey)) {
                continue;
            }
            peers.add(row);
            if (peers.size() >= MAX_VISIBLE_PEERS) {
                break;
            }
        }
        return peers;
    }

    @NonNull
    public static String peerIdentityKey(@NonNull ConversationSummary row) {
        String otherUserId = row.getOtherUserId();
        if (otherUserId != null && !otherUserId.isBlank()) {
            return otherUserId;
        }
        return row.getId();
    }
}
