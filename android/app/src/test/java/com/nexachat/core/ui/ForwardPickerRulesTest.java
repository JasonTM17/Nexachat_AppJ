package com.nexachat.core.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
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

    @Test
    public void retainedSelectedId_keepsASelectionThatStillNamesATarget() {
        assertEquals("conv-2", ForwardPickerRules.retainedSelectedId(
                "conv-2", List.of("conv-1", "conv-2", "conv-3")));
    }

    @Test
    public void retainedSelectedId_dropsASelectionWhoseTargetLeftTheList() {
        // A conversation removed before recreation must not re-enable Send.
        assertNull(ForwardPickerRules.retainedSelectedId(
                "conv-9", List.of("conv-1", "conv-2")));
        assertNull(ForwardPickerRules.retainedSelectedId(
                "conv-9", List.of()));
    }

    @Test
    public void retainedSelectedId_staysEmptyWithoutARestoredSelection() {
        assertNull(ForwardPickerRules.retainedSelectedId(
                null, List.of("conv-1")));
    }
}
