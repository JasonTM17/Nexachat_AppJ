package com.nexachat.feature.scheduled;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Test;

public final class ScheduledMessageTimeTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    public void utcIsoTruncatesToSecondPrecision() {
        assertEquals("2026-12-01T02:00:00Z",
                ScheduledMessageTime.toUtcIso(Instant.parse("2026-12-01T02:00:00.999Z")));
    }

    @Test
    public void parseAcceptsIsoAndRejectsGarbage() {
        assertEquals(NOW, ScheduledMessageTime.parse("2026-11-09T10:00:00Z"));
        assertNull(ScheduledMessageTime.parse("not-a-time"));
        assertNull(ScheduledMessageTime.parse(null));
        assertNull(ScheduledMessageTime.parse("   "));
    }

    @Test
    public void leadTimeRequiresAtLeastSixtySeconds() {
        assertFalse(ScheduledMessageTime.isWithinLeadTime(null, CLOCK));
        assertFalse(ScheduledMessageTime.isWithinLeadTime(NOW, CLOCK));
        assertFalse(ScheduledMessageTime.isWithinLeadTime(NOW.plusSeconds(59), CLOCK));
        assertTrue(ScheduledMessageTime.isWithinLeadTime(NOW.plusSeconds(60), CLOCK));
        assertTrue(ScheduledMessageTime.isWithinLeadTime(NOW.plusSeconds(3600), CLOCK));
    }

    @Test
    public void combineAndPickerDayRoundTripAcrossZones() {
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        Instant picked = ScheduledMessageTime.combine(
                Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(), 9, 30, zone);
        // 09:30 local (+07:00) is 02:30 UTC.
        assertEquals("2026-12-01T02:30:00Z", picked.toString());
        assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(),
                ScheduledMessageTime.pickerDayMillis(picked, zone));
    }
}
