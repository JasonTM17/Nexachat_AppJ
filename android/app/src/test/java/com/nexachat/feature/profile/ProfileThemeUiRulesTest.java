package com.nexachat.feature.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.nexachat.data.ThemeMode;
import org.junit.Test;

public final class ProfileThemeUiRulesTest {

    @Test
    public void hidesRowValueWhenQuickChipsAreShown() {
        assertNull(ProfileThemeUiRules.resolveThemeRowTrailingValue(
                ThemeMode.DARK, "System default", "Light", "Dark", true));
        assertNull(ProfileThemeUiRules.resolveThemeRowTrailingValue(
                ThemeMode.SYSTEM, "System default", "Light", "Dark", true));
    }

    @Test
    public void showsRowValueWhenQuickChipsAreNotShown() {
        assertEquals(
                "Dark",
                ProfileThemeUiRules.resolveThemeRowTrailingValue(
                        ThemeMode.DARK, "System default", "Light", "Dark", false));
        assertEquals(
                "Light",
                ProfileThemeUiRules.resolveThemeRowTrailingValue(
                        ThemeMode.LIGHT, "System default", "Light", "Dark", false));
    }
}
