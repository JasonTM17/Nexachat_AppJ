package com.nexachat.feature.summary;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.summary.SummaryActionItem;
import com.nexachat.summary.SummaryParticipant;
import java.util.List;

/** Resolves action-item owners to participant display names. */
public final class SummaryOwnerLabel {

    private SummaryOwnerLabel() {}

    @NonNull
    public static String displayName(
            @Nullable String ownerUserId,
            @NonNull List<SummaryParticipant> participants
    ) {
        if (ownerUserId == null || ownerUserId.isBlank()) {
            return "";
        }
        for (SummaryParticipant participant : participants) {
            if (ownerUserId.equals(participant.getUserId())) {
                String name = participant.getDisplayName();
                if (name != null && !name.isBlank()) {
                    return name.trim();
                }
                return "";
            }
        }
        return "";
    }

    @NonNull
    public static String actionMeta(
            @NonNull SummaryActionItem action,
            @NonNull List<SummaryParticipant> participants
    ) {
        StringBuilder builder = new StringBuilder();
        String owner = displayName(action.getOwnerUserId(), participants);
        if (!owner.isEmpty()) {
            builder.append(owner);
        }
        String due = action.getDueHint();
        if (due != null && !due.isBlank()) {
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append(due.trim());
        }
        return builder.toString();
    }

}
