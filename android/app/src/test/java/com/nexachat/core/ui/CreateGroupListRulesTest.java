package com.nexachat.core.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CreateGroupListRulesTest {

    @Test
    public void showLoadError_whenTheLoadFailedAndTheListIsEmpty() {
        assertTrue(CreateGroupListRules.showLoadError(false, true, true));
    }

    @Test
    public void showLoadError_notWhileLoadingOrWhenContactsExist() {
        assertFalse(CreateGroupListRules.showLoadError(true, true, true));
        assertFalse(CreateGroupListRules.showLoadError(false, false, true));
        assertFalse(CreateGroupListRules.showLoadError(false, true, false));
    }
}
