package com.nexachat.core.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GroupDetailListRulesTest {

    @Test
    public void showLoadError_whenTheLoadFailedAndTheListIsEmpty() {
        assertTrue(GroupDetailListRules.showLoadError(false, true, true));
    }

    @Test
    public void showLoadError_notWhileLoadingOrWhenMembersExist() {
        assertFalse(GroupDetailListRules.showLoadError(true, true, true));
        assertFalse(GroupDetailListRules.showLoadError(false, false, true));
        assertFalse(GroupDetailListRules.showLoadError(false, true, false));
    }
}
