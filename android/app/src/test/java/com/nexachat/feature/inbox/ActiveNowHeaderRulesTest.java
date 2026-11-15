package com.nexachat.feature.inbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Stacking rule for the Active Now header title and its trailing action. */
public final class ActiveNowHeaderRulesTest {

    @Test
    public void roomyRowKeepsTheActionBesideTheTitle() {
        // English at font scale 2.0 leaves spare width.
        assertFalse(ActiveNowHeaderRules.actionStacksBelowTitle(560f, 290f, 63f, 996f));
    }

    @Test
    public void crowdedRowStacksTheActionBelowTheTitle() {
        // Vietnamese at font scale 2.0 fills the row edge to edge.
        assertTrue(ActiveNowHeaderRules.actionStacksBelowTitle(651f, 324f, 63f, 996f));
        assertTrue(ActiveNowHeaderRules.actionStacksBelowTitle(700f, 324f, 63f, 996f));
    }
}
