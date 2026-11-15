package com.nexachat.feature.groupboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GroupBoardTabRulesTest {

    @Test
    public void directChatsHaveASingleTabAndNoStrip() {
        assertEquals(1, GroupBoardFragment.TabRules.tabCount(false));
        assertFalse(GroupBoardFragment.TabRules.stripVisible(false));
    }

    @Test
    public void groupsHaveRemindersAndPollsWithAStrip() {
        assertEquals(2, GroupBoardFragment.TabRules.tabCount(true));
        assertTrue(GroupBoardFragment.TabRules.stripVisible(true));
    }

    @Test
    public void restoredPollTabClampsIntoRemindersOnDirectBoards() {
        assertEquals(0, GroupBoardFragment.TabRules.clampSelectedTab(1, false));
    }

    @Test
    public void savedSelectionSurvivesOnGroupBoards() {
        assertEquals(0, GroupBoardFragment.TabRules.clampSelectedTab(0, true));
        assertEquals(1, GroupBoardFragment.TabRules.clampSelectedTab(1, true));
    }

    @Test
    public void outOfRangePositionsClampIntoRange() {
        assertEquals(0, GroupBoardFragment.TabRules.clampSelectedTab(-1, true));
        assertEquals(0, GroupBoardFragment.TabRules.clampSelectedTab(-1, false));
        assertEquals(1, GroupBoardFragment.TabRules.clampSelectedTab(5, true));
        assertEquals(0, GroupBoardFragment.TabRules.clampSelectedTab(5, false));
    }
}
