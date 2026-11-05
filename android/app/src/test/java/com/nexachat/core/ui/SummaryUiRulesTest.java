package com.nexachat.core.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.summary.SummaryItem;
import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryResponse;
import com.nexachat.summary.SummaryScope;
import com.nexachat.summary.SummaryUiState;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class SummaryUiRulesTest {

    @Test
    public void shouldShowBanner_returnsFalseWhenUnderMinUnread() {
        assertFalse(SummaryUiRules.shouldShowBanner(19, null, 100L));
        assertFalse(SummaryUiRules.shouldShowBanner(0, null, 100L));
    }

    @Test
    public void shouldShowBanner_returnsTrueWhenAtOrAboveMinUnreadAndNotDismissed() {
        assertTrue(SummaryUiRules.shouldShowBanner(20, null, 100L));
        assertTrue(SummaryUiRules.shouldShowBanner(50, null, 100L));
    }

    @Test
    public void shouldShowBanner_returnsFalseWhenDismissedForCurrentSeq() {
        assertFalse(SummaryUiRules.shouldShowBanner(25, 100L, 100L));
        assertFalse(SummaryUiRules.shouldShowBanner(25, 105L, 100L));
    }

    @Test
    public void shouldShowBanner_returnsTrueWhenNewMessagesArrivedAfterDismissal() {
        assertTrue(SummaryUiRules.shouldShowBanner(22, 100L, 101L));
        assertTrue(SummaryUiRules.shouldShowBanner(25, 100L, 120L));
    }

    @Test
    public void isLlmGenerated_identifiesLlmCorrectly() {
        assertTrue(SummaryUiRules.isLlmGenerated("LLM"));
        assertTrue(SummaryUiRules.isLlmGenerated("llm"));
        assertFalse(SummaryUiRules.isLlmGenerated("LOCAL"));
        assertFalse(SummaryUiRules.isLlmGenerated(null));
        assertFalse(SummaryUiRules.isLlmGenerated(""));
    }

    @Test
    public void isCitationJumpable_resolvesOnlyWhenPresentInLocalSet() {
        Set<String> localIds = new HashSet<>();
        localIds.add("msg-1");
        localIds.add("msg-2");

        assertTrue(SummaryUiRules.isCitationJumpable("msg-1", localIds));
        assertTrue(SummaryUiRules.isCitationJumpable("msg-2", localIds));
        assertFalse(SummaryUiRules.isCitationJumpable("msg-3", localIds));
        assertFalse(SummaryUiRules.isCitationJumpable(null, localIds));
        assertFalse(SummaryUiRules.isCitationJumpable("msg-1", null));
    }

    @Test
    public void canShareOrCopy_returnsFalseWhenNotReadyOrSummaryNull() {
        assertFalse(SummaryUiRules.canShareOrCopy(SummaryUiState.Status.LOADING, null));
        assertFalse(SummaryUiRules.canShareOrCopy(SummaryUiState.Status.IDLE, null));
        assertFalse(SummaryUiRules.canShareOrCopy(SummaryUiState.Status.ERROR, null));
    }

    @Test
    public void canShareOrCopy_returnsTrueWhenHasContent() {
        SummaryResponse withOverview = new SummaryResponse(
                "s1", "c1", 1, 10, 10, SummaryLanguage.VI, "LOCAL", null, null, false,
                "2026-11-05T00:00:00Z", "Overview content", null, null, null, null, null, null
        );
        assertTrue(SummaryUiRules.canShareOrCopy(SummaryUiState.Status.READY, withOverview));

        SummaryResponse withKeyPoint = new SummaryResponse(
                "s2", "c1", 1, 10, 10, SummaryLanguage.VI, "LOCAL", null, null, false,
                "2026-11-05T00:00:00Z", "", Collections.singletonList(new SummaryItem("Point 1", Collections.singletonList("m1"))),
                null, null, null, null, null
        );
        assertTrue(SummaryUiRules.canShareOrCopy(SummaryUiState.Status.READY, withKeyPoint));
    }

    @Test
    public void canShareOrCopy_returnsFalseWhenEmptySummary() {
        SummaryResponse empty = new SummaryResponse(
                "s3", "c1", 1, 10, 10, SummaryLanguage.VI, "LOCAL", null, null, false,
                "2026-11-05T00:00:00Z", "   ", null, null, null, null, null, null
        );
        assertFalse(SummaryUiRules.canShareOrCopy(SummaryUiState.Status.READY, empty));
    }

    @Test
    public void getScopeResourceName_mapsAllScopes() {
        assertEquals("summary_scope_unread", SummaryUiRules.getScopeResourceName(SummaryScope.UNREAD));
        assertEquals("summary_scope_last_n", SummaryUiRules.getScopeResourceName(SummaryScope.LAST_N));
        assertEquals("summary_scope_today", SummaryUiRules.getScopeResourceName(SummaryScope.TODAY));
    }
}
