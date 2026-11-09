package com.nexachat.feature.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.MessageMention;
import com.nexachat.data.GroupMember;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Search and authorization-aware options for the inline group mention picker. */
public final class MentionPickerRules {
    private MentionPickerRules() {}

    @NonNull
    public static List<Suggestion> suggestions(boolean isGroup, List<GroupMember> currentMembers,
            @Nullable String currentUserId, @Nullable String query, List<MessageMention> selected,
            boolean mentionAllSelected) {
        if (!isGroup || currentMembers == null || currentMembers.isEmpty()) return List.of();
        String needle = normalize(query == null ? "" : query);
        List<Suggestion> results = new ArrayList<>();
        boolean currentUserAdmin = false;
        for (GroupMember member : currentMembers) {
            if (currentUserId != null && currentUserId.equals(member.getUserId())) {
                currentUserAdmin = member.isAdmin();
            }
        }
        if (currentUserAdmin && !mentionAllSelected
                && (needle.isEmpty() || "all".contains(needle) || "everyone".contains(needle))) {
            results.add(Suggestion.all());
        }
        for (GroupMember member : currentMembers) {
            if (member.getUserId() == null || member.getDisplayName() == null
                    || isSelected(selected, member.getUserId())) continue;
            if (needle.isEmpty() || normalize(member.getDisplayName()).contains(needle)) {
                results.add(Suggestion.member(member));
            }
        }
        return List.copyOf(results);
    }

    private static boolean isSelected(List<MessageMention> selected, String userId) {
        if (selected == null) return false;
        for (MessageMention mention : selected) {
            if (userId.equals(mention.getUserId())) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replace('đ', 'd');
    }

    public static final class Suggestion {
        private final String userId;
        private final String displayName;
        private final boolean all;

        private Suggestion(String userId, String displayName, boolean all) {
            this.userId = userId;
            this.displayName = displayName;
            this.all = all;
        }

        public static Suggestion member(GroupMember member) {
            return new Suggestion(member.getUserId(), member.getDisplayName(), false);
        }

        public static Suggestion all() {
            return new Suggestion(null, null, true);
        }

        @Nullable public String getUserId() { return userId; }
        @Nullable public String getDisplayName() { return displayName; }
        public boolean isAll() { return all; }
    }
}
