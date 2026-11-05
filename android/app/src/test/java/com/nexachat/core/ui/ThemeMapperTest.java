package com.nexachat.core.ui;

import com.nexachat.data.ThemeMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThemeMapperTest {

    @Test
    public void whenSystemFollowsSystemDarkState() {
        assertTrue(ThemeMapper.resolveIsDarkTheme(ThemeMode.SYSTEM, true));
        assertFalse(ThemeMapper.resolveIsDarkTheme(ThemeMode.SYSTEM, false));
        // Null defaults to system
        assertTrue(ThemeMapper.resolveIsDarkTheme(null, true));
        assertFalse(ThemeMapper.resolveIsDarkTheme(null, false));
    }

    @Test
    public void whenLightAlwaysFalse() {
        assertFalse(ThemeMapper.resolveIsDarkTheme(ThemeMode.LIGHT, true));
        assertFalse(ThemeMapper.resolveIsDarkTheme(ThemeMode.LIGHT, false));
    }

    @Test
    public void whenDarkAlwaysTrue() {
        assertTrue(ThemeMapper.resolveIsDarkTheme(ThemeMode.DARK, true));
        assertTrue(ThemeMapper.resolveIsDarkTheme(ThemeMode.DARK, false));
    }

    @Test
    public void resolveAppearanceLightBars_invertsDarkThemeForContrast() {
        // In dark theme, status bar icons must be light (appearanceLight = false)
        assertFalse("Dark theme requires light icons", ThemeMapper.resolveAppearanceLightBars(ThemeMode.DARK, false));
        assertFalse("Dark theme requires light icons", ThemeMapper.resolveAppearanceLightBars(ThemeMode.DARK, true));
        assertFalse("System dark requires light icons", ThemeMapper.resolveAppearanceLightBars(ThemeMode.SYSTEM, true));

        // In light theme, status bar icons must be dark (appearanceLight = true)
        assertTrue("Light theme requires dark icons", ThemeMapper.resolveAppearanceLightBars(ThemeMode.LIGHT, false));
        assertTrue("Light theme requires dark icons", ThemeMapper.resolveAppearanceLightBars(ThemeMode.LIGHT, true));
        assertTrue("System light requires dark icons", ThemeMapper.resolveAppearanceLightBars(ThemeMode.SYSTEM, false));
    }

    @Test
    public void resolveThemeLabelReturnsCorrectStrings() {
        String sys = "Theo hệ thống";
        String light = "Sáng";
        String dark = "Tối";

        assertEquals(sys, ThemeMapper.resolveThemeLabel(ThemeMode.SYSTEM, sys, light, dark));
        assertEquals(light, ThemeMapper.resolveThemeLabel(ThemeMode.LIGHT, sys, light, dark));
        assertEquals(dark, ThemeMapper.resolveThemeLabel(ThemeMode.DARK, sys, light, dark));
        assertEquals(sys, ThemeMapper.resolveThemeLabel(null, sys, light, dark));
    }
}
