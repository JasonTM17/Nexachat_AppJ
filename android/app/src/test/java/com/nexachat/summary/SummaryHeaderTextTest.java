package com.nexachat.summary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Locale;
import org.junit.Test;

public final class SummaryHeaderTextTest {

    @Test
    public void headerNamesScopeDayZoneAndProcessor() {
        assertEquals("Today · 17 Nov · Asia/Ho_Chi_Minh · Local",
                SummaryHeaderText.format("Today", "17 Nov", "Asia/Ho_Chi_Minh", "Local"));
    }

    @Test
    public void headerSkipsPartsThatDoNotApply() {
        assertEquals("Unread · AI", SummaryHeaderText.format("Unread", null, null, "AI"));
        assertEquals("Last 20 · Local", SummaryHeaderText.format("Last 20", "", "  ", "Local"));
        assertEquals("", SummaryHeaderText.format(null, null, null, null));
    }

    @Test
    public void dateLabelUsesTheGivenLocale() {
        assertEquals("17 Nov", SummaryHeaderText.dateLabel("2026-11-17", Locale.ENGLISH));
        assertEquals("17 thg 11",
                SummaryHeaderText.dateLabel("2026-11-17", Locale.forLanguageTag("vi")));
        assertNull(SummaryHeaderText.dateLabel(null, Locale.ENGLISH));
        assertNull(SummaryHeaderText.dateLabel("  ", Locale.ENGLISH));
        assertNull(SummaryHeaderText.dateLabel("not-a-date", Locale.ENGLISH));
    }
}
