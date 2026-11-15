package com.nexachat.feature.inbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Stacking rule for the Active Now header title and its trailing action. */
public final class ActiveNowHeaderRulesTest {

    @Test
    public void roomyRowKeepsTheActionBesideTheTitle() {
        // English at font scale 2.0 leaves spare width.
        assertFalse(ActiveNowHeaderRules.actionStacksBelowTitle(560f, 290f, 21f, 996f));
        // Vietnamese at font scale 2.0 (~640 px title on a 1080 px row) still
        // fits inside the real 8 dp gap, so it stays beside the action.
        assertFalse(ActiveNowHeaderRules.actionStacksBelowTitle(640f, 324f, 21f, 996f));
        // Exactly edge to edge still counts as fitting.
        assertFalse(ActiveNowHeaderRules.actionStacksBelowTitle(651f, 324f, 21f, 996f));
    }

    @Test
    public void crowdedRowStacksTheActionBelowTheTitle() {
        // Once the measured widths overflow the real gap the action drops.
        assertTrue(ActiveNowHeaderRules.actionStacksBelowTitle(700f, 324f, 21f, 996f));
        assertTrue(ActiveNowHeaderRules.actionStacksBelowTitle(800f, 324f, 21f, 996f));
    }
}
