package com.nexachat.core.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ForwardPickerRulesTest {

    @Test
    public void body_prefersLoadingThenErrorThenEmpty() {
        assertEquals(ForwardPickerRules.Body.LOADING, ForwardPickerRules.body(true, false, 0));
        assertEquals(ForwardPickerRules.Body.ERROR, ForwardPickerRules.body(false, true, 0));
        assertEquals(ForwardPickerRules.Body.EMPTY, ForwardPickerRules.body(false, false, 0));
    }

    @Test
    public void body_keepsVisibleMatchesDuringARefresh() {
        assertEquals(ForwardPickerRules.Body.LIST, ForwardPickerRules.body(true, true, 2));
    }
}
