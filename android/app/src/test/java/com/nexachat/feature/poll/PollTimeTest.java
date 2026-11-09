package com.nexachat.feature.poll;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.Test;

public final class PollTimeTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    @Test
    public void mapsAPickedDayToTheInclusiveEndOfThatLocalDay() {
        long day = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli();
        assertEquals("2026-12-01T23:59:59Z", PollTime.endOfDay(day, ZoneOffset.UTC).toString());
        assertEquals(day, PollTime.pickerDayMillis(
                Instant.parse("2026-12-01T05:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    public void neverOffersAnExpiryDayWhoseEndExceedsTheServerWindow() {
        long day = PollTime.maxExpiryDayMillis(NOW, ZoneOffset.UTC, 30);
        assertEquals("2026-12-08T00:00:00Z", Instant.ofEpochMilli(day).toString());
        assertEquals(PollValidation.OK, new PollDraft(
                "Q", java.util.List.of("A", "B"), false, false,
                PollTime.endOfDay(day, ZoneOffset.UTC)).validate(NOW));
    }
}
