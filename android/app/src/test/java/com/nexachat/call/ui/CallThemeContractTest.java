package com.nexachat.call.ui;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/** CallActivity uses Material buttons; the call theme must descend from Material Components. */
public final class CallThemeContractTest {

    @Test
    public void callThemeParentsMaterial3() throws Exception {
        String day = new String(
                Files.readAllBytes(Path.of("src/main/res/values/styles_call.xml")),
                StandardCharsets.UTF_8);
        String night = new String(
                Files.readAllBytes(Path.of("src/main/res/values-night/styles_call.xml")),
                StandardCharsets.UTF_8);
        assertTrue(day.contains("parent=\"Theme.Material3.DayNight.NoActionBar\""));
        assertTrue(night.contains("parent=\"Theme.Material3.DayNight.NoActionBar\""));
    }
}
