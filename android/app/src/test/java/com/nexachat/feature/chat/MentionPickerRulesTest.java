package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.network.dto.MessageMention;
import com.nexachat.data.GroupMember;
import java.util.List;
import org.junit.Test;

public final class MentionPickerRulesTest {
    private final List<GroupMember> members = List.of(
            new GroupMember("self", "Sơn", null, null, GroupMember.ROLE_ADMIN),
            new GroupMember("lan", "Đặng Thị Lan", null, null, "MEMBER"),
            new GroupMember("mai", "Mai Anh", null, null, "MEMBER"));

    @Test public void pickerUsesOnlyGroupMembersAndAccentInsensitiveSearch() {
        assertTrue(MentionPickerRules.suggestions(false, members, "self", "", List.of(), false).isEmpty());
        List<MentionPickerRules.Suggestion> suggestions = MentionPickerRules.suggestions(
                true, members, "self", "dang thi", List.of(), false);
        assertEquals(1, suggestions.size());
        assertEquals("Đặng Thị Lan", suggestions.get(0).getDisplayName());
    }

    @Test public void onlyCurrentGroupAdminsSeeAllAndSelectedMembersDisappear() {
        List<MentionPickerRules.Suggestion> admin = MentionPickerRules.suggestions(
                true, members, "self", "a", List.of(new MessageMention("lan", 0, 4)), false);
        assertTrue(admin.stream().anyMatch(MentionPickerRules.Suggestion::isAll));
        assertFalse(admin.stream().anyMatch(item -> "lan".equals(item.getUserId())));

        List<MentionPickerRules.Suggestion> member = MentionPickerRules.suggestions(
                true, members, "lan", "all", List.of(), false);
        assertTrue(member.stream().noneMatch(MentionPickerRules.Suggestion::isAll));
    }
}
