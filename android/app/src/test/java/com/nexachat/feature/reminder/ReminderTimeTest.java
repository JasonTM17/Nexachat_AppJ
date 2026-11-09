package com.nexachat.feature.reminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Test;

public final class ReminderTimeTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);

    @Test
    public void combinesAPickedUtcDayWithLocalTime() {
        long day = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli();
        Instant combined = ReminderTime.combine(day, 9, 30, ZONE);

        assertEquals("2026-12-01T02:30:00Z", combined.toString());
        assertEquals(day, ReminderTime.pickerDayMillis(combined, ZONE));
    }

    @Test
    public void serializesAtSecondPrecisionForTheServer() {
        Instant instant = Instant.parse("2026-12-01T02:30:45.987Z");
        assertEquals("2026-12-01T02:30:45Z", ReminderTime.toUtcIso(instant));
        assertEquals(Instant.parse("2026-12-01T02:30:45Z"),
                ReminderTime.parse(ReminderTime.toUtcIso(instant)));
        assertNull(ReminderTime.parse("not-a-time"));
    }

    @Test
    public void acceptsOnlyFutureTimesWithinOneYear() {
        assertFalse(ReminderTime.isFutureWithinYear(
                CLOCK.instant().minusSeconds(1), CLOCK));
        assertFalse(ReminderTime.isFutureWithinYear(null, CLOCK));
        assertTrue(ReminderTime.isFutureWithinYear(
                CLOCK.instant().plusSeconds(60), CLOCK));
        assertTrue(ReminderTime.isFutureWithinYear(
                CLOCK.instant().plus(ReminderTime.MAX_LEAD_TIME), CLOCK));
        assertFalse(ReminderTime.isFutureWithinYear(
                CLOCK.instant().plus(ReminderTime.MAX_LEAD_TIME).plusSeconds(1), CLOCK));
    }
}
