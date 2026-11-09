package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;

public final class MutePresetsTest {

    private static final ZoneId SAIGON = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    public void oneHourAddsExactlyOneHour() {
        Instant now = Instant.parse("2026-11-09T03:15:00Z");
        assertEquals(Instant.parse("2026-11-09T04:15:00Z"), MutePresets.oneHour(now));
    }

    @Test
    public void fourHoursAddsExactlyFourHours() {
        Instant now = Instant.parse("2026-11-09T03:15:00Z");
        assertEquals(Instant.parse("2026-11-09T07:15:00Z"), MutePresets.fourHours(now));
    }

    @Test
    public void beforeEightUsesTodaysEight() {
        // 07:30 local (UTC+7).
        Instant now = Instant.parse("2026-11-09T00:30:00Z");
        assertEquals(Instant.parse("2026-11-09T01:00:00Z"),
                MutePresets.untilNextEightAm(now, SAIGON));
    }

    @Test
    public void oneMillisecondBeforeEightStillUsesTodaysEight() {
        Instant now = Instant.parse("2026-11-09T00:59:59.999Z");
        assertEquals(Instant.parse("2026-11-09T01:00:00Z"),
                MutePresets.untilNextEightAm(now, SAIGON));
    }

    @Test
    public void exactlyEightMovesToTomorrowBecauseTheInstantMustBeInTheFuture() {
        Instant now = Instant.parse("2026-11-09T01:00:00Z");
        assertEquals(Instant.parse("2026-11-10T01:00:00Z"),
                MutePresets.untilNextEightAm(now, SAIGON));
    }

    @Test
    public void afterEightUsesTomorrowsEight() {
        // 23:30 local.
        Instant now = Instant.parse("2026-11-09T16:30:00Z");
        assertEquals(Instant.parse("2026-11-10T01:00:00Z"),
                MutePresets.untilNextEightAm(now, SAIGON));
    }

    @Test
    public void resultIsStrictlyInTheFutureAtTheBoundary() {
        Instant now = Instant.parse("2026-11-09T01:00:00Z");
        assertTrue(MutePresets.untilNextEightAm(now, SAIGON).isAfter(now));
    }
}
