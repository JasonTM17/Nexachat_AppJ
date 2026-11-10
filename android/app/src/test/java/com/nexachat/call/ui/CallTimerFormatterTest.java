package com.nexachat.call.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CallTimerFormatterTest {
    private static final String PATTERN = "%02d:%02d";

    @Test public void formatsMinutesAndSecondsWithoutWrappingAtAnHour() {
        assertEquals("00:00", CallTimerFormatter.formatSeconds(0, PATTERN));
        assertEquals("01:05", CallTimerFormatter.formatSeconds(65, PATTERN));
        assertEquals("60:00", CallTimerFormatter.formatSeconds(3600, PATTERN));
        assertEquals("00:00", CallTimerFormatter.formatSeconds(-3, PATTERN));
    }
}
