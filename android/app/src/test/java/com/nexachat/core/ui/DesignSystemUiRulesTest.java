package com.nexachat.core.ui;

import com.nexachat.core.ui.components.AvatarPalette;
import com.nexachat.core.ui.components.ChatUiRules;
import com.nexachat.core.ui.components.DateSeparatorLabels;
import com.nexachat.core.ui.components.LinkUtils;
import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure UI presentation rule tests verifying design system contracts:
 * - Message grouping state resolution &amp; shape assignment
 * - Date separator triggers &amp; localized label buckets
 * - Inbox relative timestamp buckets
 * - Deterministic avatar palette mapping &amp; WCAG contrast compliance
 * - Safe URL link sanitization (HTTP/HTTPS only)
 */
public class DesignSystemUiRulesTest {

    // --- Message Grouping & Bubble Shapes ---

    @Test
    public void resolveGroupPositionForIsolatedMessageReturnsSingle() {
        ChatMessage msg = createMessage("m1", "u1", 10_000L);
        MessageGroupPosition pos = ChatUiRules.resolveGroupPosition(Collections.singletonList(msg), 0);
        assertEquals(MessageGroupPosition.SINGLE, pos);
    }

    @Test
    public void resolveGroupPositionForTwoConsecutiveSameSenderMessages() {
        ChatMessage m1 = createMessage("m1", "u1", 10_000L);
        ChatMessage m2 = createMessage("m2", "u1", 20_000L);
        java.util.List<ChatMessage> list = Arrays.asList(m1, m2);

        assertEquals("First message in sequence must be FIRST",
                MessageGroupPosition.FIRST, ChatUiRules.resolveGroupPosition(list, 0));
        assertEquals("Last message in sequence must be LAST",
                MessageGroupPosition.LAST, ChatUiRules.resolveGroupPosition(list, 1));
    }

    @Test
    public void resolveGroupPositionForThreeConsecutiveSameSenderMessages() {
        ChatMessage m1 = createMessage("m1", "u1", 10_000L);
        ChatMessage m2 = createMessage("m2", "u1", 20_000L);
        ChatMessage m3 = createMessage("m3", "u1", 30_000L);
        java.util.List<ChatMessage> list = Arrays.asList(m1, m2, m3);

        assertEquals(MessageGroupPosition.FIRST, ChatUiRules.resolveGroupPosition(list, 0));
        assertEquals(MessageGroupPosition.MIDDLE, ChatUiRules.resolveGroupPosition(list, 1));
        assertEquals(MessageGroupPosition.LAST, ChatUiRules.resolveGroupPosition(list, 2));
    }

    @Test
    public void resolveGroupPositionDifferentSenderBreaksGroup() {
        ChatMessage m1 = createMessage("m1", "u1", 10_000L);
        ChatMessage m2 = createMessage("m2", "u2", 20_000L);
        java.util.List<ChatMessage> list = Arrays.asList(m1, m2);

        assertEquals(MessageGroupPosition.SINGLE, ChatUiRules.resolveGroupPosition(list, 0));
        assertEquals(MessageGroupPosition.SINGLE, ChatUiRules.resolveGroupPosition(list, 1));
    }

    @Test
    public void resolveGroupPositionExceedingIntervalBreaksGroup() {
        ChatMessage m1 = createMessage("m1", "u1", 10_000L);
        ChatMessage m2 = createMessage("m2", "u1", 10_000L + 6 * 60 * 1000L); // 6 min > 5 min max
        java.util.List<ChatMessage> list = Arrays.asList(m1, m2);

        assertEquals(MessageGroupPosition.SINGLE, ChatUiRules.resolveGroupPosition(list, 0));
        assertEquals(MessageGroupPosition.SINGLE, ChatUiRules.resolveGroupPosition(list, 1));
    }

    @Test
    public void resolveBubbleShapeMineCorners() {
        // Outgoing: lower end corner is 4dp only for SINGLE and LAST.
        ChatUiRules.BubbleCornerRadii single =
                ChatUiRules.resolveBubbleShape(MessageGroupPosition.SINGLE, true);
        ChatUiRules.BubbleCornerRadii first =
                ChatUiRules.resolveBubbleShape(MessageGroupPosition.FIRST, true);
        ChatUiRules.BubbleCornerRadii middle =
                ChatUiRules.resolveBubbleShape(MessageGroupPosition.MIDDLE, true);
        ChatUiRules.BubbleCornerRadii last =
                ChatUiRules.resolveBubbleShape(MessageGroupPosition.LAST, true);

        assertNotNull(single);
        assertNotNull(first);
        assertNotNull(middle);
        assertNotNull(last);

        assertRadii(single, 16f, 16f, 16f, 4f);
        assertRadii(first, 16f, 16f, 16f, 16f);
        assertRadii(middle, 16f, 16f, 16f, 16f);
        assertRadii(last, 16f, 16f, 16f, 4f);
    }

    @Test
    public void resolveBubbleShapePeerCorners() {
        // Incoming: lower start corner is 4dp only for SINGLE and LAST.
        ChatUiRules.BubbleCornerRadii single =
                ChatUiRules.resolveBubbleShape(MessageGroupPosition.SINGLE, false);
        ChatUiRules.BubbleCornerRadii first =
                ChatUiRules.resolveBubbleShape(MessageGroupPosition.FIRST, false);
        ChatUiRules.BubbleCornerRadii middle =
                ChatUiRules.resolveBubbleShape(MessageGroupPosition.MIDDLE, false);
        ChatUiRules.BubbleCornerRadii last =
                ChatUiRules.resolveBubbleShape(MessageGroupPosition.LAST, false);

        assertNotNull(single);
        assertNotNull(first);
        assertNotNull(middle);
        assertNotNull(last);

        assertRadii(single, 16f, 16f, 4f, 16f);
        assertRadii(first, 16f, 16f, 16f, 16f);
        assertRadii(middle, 16f, 16f, 16f, 16f);
        assertRadii(last, 16f, 16f, 4f, 16f);
    }

    // --- Date Separator Rules ---

    @Test
    public void shouldShowDateSeparatorReturnsTrueForThreadStart() {
        assertTrue("Initial message in thread must trigger date separator",
                ChatUiRules.shouldShowDateSeparator(null, 1_000_000L));
    }

    @Test
    public void shouldShowDateSeparatorReturnsFalseForSameCalendarDay() {
        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.NOVEMBER, 5, 9, 0, 0);
        long t1 = cal.getTimeInMillis();
        cal.set(Calendar.HOUR_OF_DAY, 14);
        long t2 = cal.getTimeInMillis();

        assertFalse("Messages on the same day must not trigger date separator",
                ChatUiRules.shouldShowDateSeparator(t1, t2));
    }

    @Test
    public void shouldShowDateSeparatorReturnsTrueAcrossMidnight() {
        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.NOVEMBER, 5, 23, 55, 0);
        long t1 = cal.getTimeInMillis();
        cal.set(2026, Calendar.NOVEMBER, 6, 0, 5, 0);
        long t2 = cal.getTimeInMillis();

        assertTrue("Midnight boundary must trigger date separator",
                ChatUiRules.shouldShowDateSeparator(t1, t2));
    }

    @Test
    public void resolveDateSeparatorLabelBuckets() {
        DateSeparatorLabels labels = new DateSeparatorLabels("Hôm nay", "Hôm qua");
        Calendar nowCal = Calendar.getInstance();
        nowCal.set(2026, Calendar.NOVEMBER, 5, 12, 0, 0);
        long nowMs = nowCal.getTimeInMillis();

        // Today
        assertEquals("Hôm nay", ChatUiRules.resolveDateSeparatorLabel(nowMs, nowMs, labels, Locale.US));

        // Yesterday
        Calendar yesterdayCal = (Calendar) nowCal.clone();
        yesterdayCal.add(Calendar.DAY_OF_YEAR, -1);
        assertEquals("Hôm qua",
                ChatUiRules.resolveDateSeparatorLabel(yesterdayCal.getTimeInMillis(), nowMs, labels, Locale.US));

        // Same year earlier
        Calendar sameYearCal = (Calendar) nowCal.clone();
        sameYearCal.set(2026, Calendar.OCTOBER, 15);
        assertEquals("15/10",
                ChatUiRules.resolveDateSeparatorLabel(sameYearCal.getTimeInMillis(), nowMs, labels, Locale.US));

        // Previous year
        Calendar prevYearCal = (Calendar) nowCal.clone();
        prevYearCal.set(2025, Calendar.DECEMBER, 25);
        assertEquals("25/12/2025",
                ChatUiRules.resolveDateSeparatorLabel(prevYearCal.getTimeInMillis(), nowMs, labels, Locale.US));
    }

    // --- Relative Timestamp Formatting ---

    @Test
    public void resolveRelativeTimeLabelBuckets() {
        Calendar nowCal = Calendar.getInstance();
        nowCal.set(2026, Calendar.NOVEMBER, 5, 14, 30, 0);
        long nowMs = nowCal.getTimeInMillis();

        // < 1 minute
        long justNowMs = nowMs - 30_000L;
        assertEquals("Vừa xong",
                ChatUiRules.resolveRelativeTimeLabel(justNowMs, nowMs, "Vừa xong", "Hôm qua", Locale.US));

        // Same day > 1 minute
        long sameDayMs = nowMs - 2 * 3600 * 1000L; // 12:30
        assertEquals("12:30",
                ChatUiRules.resolveRelativeTimeLabel(sameDayMs, nowMs, "Vừa xong", "Hôm qua", Locale.US));

        // Yesterday
        Calendar yesterdayCal = (Calendar) nowCal.clone();
        yesterdayCal.add(Calendar.DAY_OF_YEAR, -1);
        assertEquals("Hôm qua",
                ChatUiRules.resolveRelativeTimeLabel(yesterdayCal.getTimeInMillis(), nowMs, "Vừa xong", "Hôm qua", Locale.US));

        // Same year earlier
        Calendar sameYearCal = (Calendar) nowCal.clone();
        sameYearCal.set(2026, Calendar.OCTOBER, 20);
        assertEquals("20/10",
                ChatUiRules.resolveRelativeTimeLabel(sameYearCal.getTimeInMillis(), nowMs, "Vừa xong", "Hôm qua", Locale.US));
    }

    // --- Avatar Palette Determinism & Contrast ---

    @Test
    public void avatarPaletteIndexIsDeterministicAndNormalised() {
        int idx1 = AvatarPalette.avatarPaletteIndex("Nguyen Van A");
        int idx2 = AvatarPalette.avatarPaletteIndex("nguyen van a");
        int idx3 = AvatarPalette.avatarPaletteIndex("  Nguyen Van A  ");
        assertEquals("Case and trimming differences must produce identical palette index", idx1, idx2);
        assertEquals(idx1, idx3);
        assertTrue("Palette index must be in range 0..7", idx1 >= 0 && idx1 <= 7);
    }

    @Test
    public void extractInitialsHandlesSingleAndMultipleWords() {
        assertEquals("NA", AvatarPalette.extractInitials("Nguyen An"));
        assertEquals("B", AvatarPalette.extractInitials("Binh"));
        assertEquals("?", AvatarPalette.extractInitials(""));
        assertEquals("?", AvatarPalette.extractInitials("   "));
    }

    @Test
    public void avatarPalettesMeetWcagAaContrastRatio() {
        // Test all 8 palette hues in both Light and Dark modes against their initials text color
        String[] names = {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel"};
        for (String name : names) {
            AvatarPalette.ColorPair lightColors = AvatarPalette.avatarColorsForName(name, false);
            double lightRatio = computeContrastRatio(
                    lightColors.getContainer(),
                    lightColors.getOnContainer());
            assertTrue("Light mode avatar '" + name + "' contrast " + lightRatio + " must be >= 4.5:1",
                    lightRatio >= 4.5);

            AvatarPalette.ColorPair darkColors = AvatarPalette.avatarColorsForName(name, true);
            double darkRatio = computeContrastRatio(
                    darkColors.getContainer(),
                    darkColors.getOnContainer());
            assertTrue("Dark mode avatar '" + name + "' contrast " + darkRatio + " must be >= 4.5:1",
                    darkRatio >= 4.5);
        }
    }

    // --- URL Link Sanitizer ---

    @Test
    public void sanitizeLinkUrlPermitsHttpAndHttpsOnly() {
        assertEquals("http://example.com", LinkUtils.sanitizeLinkUrl("http://example.com"));
        assertEquals("https://example.com/chat?id=123", LinkUtils.sanitizeLinkUrl("https://example.com/chat?id=123"));
        assertEquals("https://sub.domain.org/path#anchor", LinkUtils.sanitizeLinkUrl("https://sub.domain.org/path#anchor"));

        // Malicious or unsupported URI schemes must be rejected (return null)
        assertNull("javascript: URI must be rejected", LinkUtils.sanitizeLinkUrl("javascript:alert(1)"));
        assertNull("file: URI must be rejected", LinkUtils.sanitizeLinkUrl("file:///android_asset/secret.txt"));
        assertNull("intent: URI must be rejected", LinkUtils.sanitizeLinkUrl("intent://run#Intent;scheme=app;end"));
        assertNull("ftp: URI must be rejected", LinkUtils.sanitizeLinkUrl("ftp://ftp.example.com"));
        assertNull("content: URI must be rejected", LinkUtils.sanitizeLinkUrl("content://media/external/images"));
        assertNull("Bare string must be rejected", LinkUtils.sanitizeLinkUrl("not_a_valid_url"));
    }

    // --- Helpers ---

    private static ChatMessage createMessage(String id, String senderId, long epochMs) {
        return new ChatMessage(
                id,
                "conv-1",
                1L,
                senderId,
                "User " + senderId,
                "TEXT",
                "Message " + id,
                null,
                null,
                null,
                epochMs,
                null,
                DeliveryStatus.SENT,
                "me".equals(senderId),
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null
        );
    }

    private static void assertRadii(
            ChatUiRules.BubbleCornerRadii radii,
            float topStart,
            float topEnd,
            float bottomStart,
            float bottomEnd
    ) {
        assertEquals(topStart, radii.topStartDp, 0.001);
        assertEquals(topEnd, radii.topEndDp, 0.001);
        assertEquals(bottomStart, radii.bottomStartDp, 0.001);
        assertEquals(bottomEnd, radii.bottomEndDp, 0.001);
    }

    private static double computeContrastRatio(int c1, int c2) {
        double l1 = relativeLuminance(c1);
        double l2 = relativeLuminance(c2);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double relativeLuminance(int argb) {
        double r = ((argb >> 16) & 0xFF) / 255.0;
        double g = ((argb >> 8) & 0xFF) / 255.0;
        double b = (argb & 0xFF) / 255.0;
        return 0.2126 * channelValue(r) + 0.7152 * channelValue(g) + 0.0722 * channelValue(b);
    }

    private static double channelValue(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
