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
  public void stacksWhenTheTitleWouldWrapBesideAnInlineValue() {
    int rowWidthPx = Math.round(380 * DENSITY);
    float longTitlePx = 150 * DENSITY;
    float shortTitlePx = 70 * DENSITY;
    assertTrue(
        SettingsRowLayoutRules.shouldStackTrailingValue(
            DENSITY, rowWidthPx, true, true, true, longTitlePx));
    assertFalse(
        SettingsRowLayoutRules.shouldStackTrailingValue(
            DENSITY, rowWidthPx, true, true, true, shortTitlePx));
  }

  @Test
  public void stacksAtLargeFontScaleWhenTitleTextExceedsInlineSpace() {
    // font_scale 2.0 on the 996px row: scaled "Ngôn ngữ" paint width exceeds the
    // space left after icon, value cap and chevron, so the value must stack.
    int rowWidthPx = 996;
    float scaledTitlePx = 700f;
    assertTrue(
        SettingsRowLayoutRules.shouldStackTrailingValue(
            2.625f, rowWidthPx, true, true, true, scaledTitlePx));
  }

  @Test
  public void staysInlineAtNormalFontWhenTitleFits() {
    int rowWidthPx = 996;
    float titlePx = 200f;
    assertFalse(
        SettingsRowLayoutRules.shouldStackTrailingValue(
            2.625f, rowWidthPx, true, true, true, titlePx));
  }

  @Test
  public void neverStacksWithoutTrailingValue() {
    int rowWidthPx = Math.round(320 * DENSITY);
    assertFalse(
        SettingsRowLayoutRules.shouldStackTrailingValue(
            DENSITY, rowWidthPx, false, true, true));
  }
}
