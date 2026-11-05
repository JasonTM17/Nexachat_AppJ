package com.nexachat.call.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CallTimerFormatterTest {
    @Test public void formatsMinutesAndSecondsWithoutWrappingAtAnHour() {
        assertEquals("00:00", CallTimerFormatter.formatSeconds(0));
        assertEquals("01:05", CallTimerFormatter.formatSeconds(65));
        assertEquals("60:00", CallTimerFormatter.formatSeconds(3600));
        assertEquals("00:00", CallTimerFormatter.formatSeconds(-3));
    }
}
