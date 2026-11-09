package com.nexachat.feature.archive;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import org.junit.Test;

public final class ArchiveDateRangeTest {

    @Test
    public void pickerDaysBecomeInclusiveIsoBounds() {
        long start = Instant.parse("2026-11-09T00:00:00Z").toEpochMilli();
        long end = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli();

        assertEquals("2026-11-09T00:00:00Z", ArchiveDateRange.startOfDay(start));
        assertEquals("2026-11-10T23:59:59.999999999Z", ArchiveDateRange.endOfDay(end));
        assertEquals(Long.valueOf(start), ArchiveDateRange.toPickerMillis(
                "2026-11-09T23:59:59.999999999Z"));
    }
}
