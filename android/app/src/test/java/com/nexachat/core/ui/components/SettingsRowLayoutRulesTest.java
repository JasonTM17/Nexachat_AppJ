package com.nexachat.core.ui.components;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SettingsRowLayoutRulesTest {

  private static final float DENSITY = 2f;

  @Test
  public void stacksTrailingValueWhenTitleWouldBeTooNarrowAt320dp() {
    int rowWidthPx = Math.round(320 * DENSITY);
    assertTrue(
        SettingsRowLayoutRules.shouldStackTrailingValue(
            DENSITY, rowWidthPx, true, true, true));
  }

  @Test
  public void keepsInlineValueWhenRowIsWideEnough() {
    int rowWidthPx = Math.round(412 * DENSITY);
    assertFalse(
        SettingsRowLayoutRules.shouldStackTrailingValue(
            DENSITY, rowWidthPx, true, true, true));
  }

  @Test
  public void neverStacksWithoutTrailingValue() {
    int rowWidthPx = Math.round(320 * DENSITY);
    assertFalse(
        SettingsRowLayoutRules.shouldStackTrailingValue(
            DENSITY, rowWidthPx, false, true, true));
  }
}
