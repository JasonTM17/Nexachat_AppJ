package com.nexachat.feature.group;

import static org.junit.Assert.assertEquals;

import com.nexachat.R;
import com.nexachat.feature.group.adapter.GroupMemberAdapter;
import org.junit.Test;

public final class GroupMemberRemoveContentDescriptionTest {

    @Test
    public void removeMemberUsesMemberNamedConfirmString() {
        assertEquals(R.string.group_remove_member_confirm, GroupMemberAdapter.removeMemberContentDescriptionRes());
    }
}
