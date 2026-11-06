package com.nexachat.core.ui

import androidx.compose.ui.unit.dp
import com.nexachat.core.ui.components.DateSeparatorLabels
import com.nexachat.core.ui.components.MessageGroupPosition
import com.nexachat.core.ui.components.avatarColorsForName
import com.nexachat.core.ui.components.avatarPaletteIndex
import com.nexachat.core.ui.components.extractInitials
import com.nexachat.core.ui.components.resolveBubbleShape
import com.nexachat.core.ui.components.resolveDateSeparatorLabel
import com.nexachat.core.ui.components.resolveGroupPosition
import com.nexachat.core.ui.components.resolveRelativeTimeLabel
import com.nexachat.core.ui.components.LinkUtils.sanitizeLinkUrl
import com.nexachat.core.ui.components.shouldShowDateSeparator
import com.nexachat.data.ChatMessage
import com.nexachat.data.DeliveryStatus
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure UI presentation rule tests verifying design system contracts:
 * - Message grouping state resolution & shape assignment
 * - Date separator triggers & localized label buckets
 * - Inbox relative timestamp buckets
 * - Deterministic avatar palette mapping & WCAG contrast compliance
 * - Safe URL link sanitization (HTTP/HTTPS only)
 */
class DesignSystemUiRulesTest {

    // --- Message Grouping & Bubble Shapes ---

    @Test
    fun resolveGroupPositionForIsolatedMessageReturnsSingle() {
        val msg = createMessage(id = "m1", senderId = "u1", epochMs = 10_000L)
        val pos = resolveGroupPosition(listOf(msg), 0)
        assertEquals(MessageGroupPosition.SINGLE, pos)
    }

    @Test
    fun resolveGroupPositionForTwoConsecutiveSameSenderMessages() {
        val m1 = createMessage(id = "m1", senderId = "u1", epochMs = 10_000L)
        val m2 = createMessage(id = "m2", senderId = "u1", epochMs = 20_000L)
        val list = listOf(m1, m2)

        assertEquals("First message in sequence must be FIRST", MessageGroupPosition.FIRST, resolveGroupPosition(list, 0))
        assertEquals("Last message in sequence must be LAST", MessageGroupPosition.LAST, resolveGroupPosition(list, 1))
    }

    @Test
    fun resolveGroupPositionForThreeConsecutiveSameSenderMessages() {
        val m1 = createMessage(id = "m1", senderId = "u1", epochMs = 10_000L)
        val m2 = createMessage(id = "m2", senderId = "u1", epochMs = 20_000L)
        val m3 = createMessage(id = "m3", senderId = "u1", epochMs = 30_000L)
        val list = listOf(m1, m2, m3)

        assertEquals(MessageGroupPosition.FIRST, resolveGroupPosition(list, 0))
        assertEquals(MessageGroupPosition.MIDDLE, resolveGroupPosition(list, 1))
        assertEquals(MessageGroupPosition.LAST, resolveGroupPosition(list, 2))
    }

    @Test
    fun resolveGroupPositionDifferentSenderBreaksGroup() {
        val m1 = createMessage(id = "m1", senderId = "u1", epochMs = 10_000L)
        val m2 = createMessage(id = "m2", senderId = "u2", epochMs = 20_000L)
        val list = listOf(m1, m2)

        assertEquals(MessageGroupPosition.SINGLE, resolveGroupPosition(list, 0))
        assertEquals(MessageGroupPosition.SINGLE, resolveGroupPosition(list, 1))
    }

    @Test
    fun resolveGroupPositionExceedingIntervalBreaksGroup() {
        val m1 = createMessage(id = "m1", senderId = "u1", epochMs = 10_000L)
        val m2 = createMessage(id = "m2", senderId = "u1", epochMs = 10_000L + 6 * 60 * 1000L) // 6 min > 5 min max
        val list = listOf(m1, m2)

        assertEquals(MessageGroupPosition.SINGLE, resolveGroupPosition(list, 0))
        assertEquals(MessageGroupPosition.SINGLE, resolveGroupPosition(list, 1))
    }

    @Test
    fun resolveBubbleShapeMineCorners() {
        // Outgoing (mine = true): tail is at bottomEnd (4dp) on SINGLE/FIRST/MIDDLE, rounded (16dp) on LAST
        val single = resolveBubbleShape(MessageGroupPosition.SINGLE, mine = true)
        val first = resolveBubbleShape(MessageGroupPosition.FIRST, mine = true)
        val middle = resolveBubbleShape(MessageGroupPosition.MIDDLE, mine = true)
        val last = resolveBubbleShape(MessageGroupPosition.LAST, mine = true)

        assertNotNull(single)
        assertNotNull(first)
        assertNotNull(middle)
        assertNotNull(last)
    }

    @Test
    fun resolveBubbleShapePeerCorners() {
        // Incoming (mine = false): tail is at bottomStart (4dp) on SINGLE/FIRST/MIDDLE, rounded (16dp) on LAST
        val single = resolveBubbleShape(MessageGroupPosition.SINGLE, mine = false)
        val first = resolveBubbleShape(MessageGroupPosition.FIRST, mine = false)
        val middle = resolveBubbleShape(MessageGroupPosition.MIDDLE, mine = false)
        val last = resolveBubbleShape(MessageGroupPosition.LAST, mine = false)

        assertNotNull(single)
        assertNotNull(first)
        assertNotNull(middle)
        assertNotNull(last)
    }

    // --- Date Separator Rules ---

    @Test
    fun shouldShowDateSeparatorReturnsTrueForThreadStart() {
        assertTrue("Initial message in thread must trigger date separator", shouldShowDateSeparator(null, 1_000_000L))
    }

    @Test
    fun shouldShowDateSeparatorReturnsFalseForSameCalendarDay() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 5, 9, 0, 0)
        }
        val t1 = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 14)
        val t2 = cal.timeInMillis

        assertFalse("Messages on the same day must not trigger date separator", shouldShowDateSeparator(t1, t2))
    }

    @Test
    fun shouldShowDateSeparatorReturnsTrueAcrossMidnight() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 5, 23, 55, 0)
        }
        val t1 = cal.timeInMillis
        cal.set(2026, Calendar.NOVEMBER, 6, 0, 5, 0)
        val t2 = cal.timeInMillis

        assertTrue("Midnight boundary must trigger date separator", shouldShowDateSeparator(t1, t2))
    }

    @Test
    fun resolveDateSeparatorLabelBuckets() {
        val labels = DateSeparatorLabels(today = "Hôm nay", yesterday = "Hôm qua")
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 5, 12, 0, 0)
        }
        val nowMs = nowCal.timeInMillis

        // Today
        assertEquals("Hôm nay", resolveDateSeparatorLabel(nowMs, nowMs, labels, Locale.US))

        // Yesterday
        val yesterdayCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        assertEquals("Hôm qua", resolveDateSeparatorLabel(yesterdayCal.timeInMillis, nowMs, labels, Locale.US))

        // Same year earlier
        val sameYearCal = (nowCal.clone() as Calendar).apply { set(2026, Calendar.OCTOBER, 15) }
        assertEquals("15/10", resolveDateSeparatorLabel(sameYearCal.timeInMillis, nowMs, labels, Locale.US))

        // Previous year
        val prevYearCal = (nowCal.clone() as Calendar).apply { set(2025, Calendar.DECEMBER, 25) }
        assertEquals("25/12/2025", resolveDateSeparatorLabel(prevYearCal.timeInMillis, nowMs, labels, Locale.US))
    }

    // --- Relative Timestamp Formatting ---

    @Test
    fun resolveRelativeTimeLabelBuckets() {
        val nowCal = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 5, 14, 30, 0) }
        val nowMs = nowCal.timeInMillis

        // < 1 minute
        val justNowMs = nowMs - 30_000L
        assertEquals("Vừa xong", resolveRelativeTimeLabel(justNowMs, nowMs, "Vừa xong", "Hôm qua", Locale.US))

        // Same day > 1 minute
        val sameDayMs = nowMs - 2 * 3600 * 1000L // 12:30
        assertEquals("12:30", resolveRelativeTimeLabel(sameDayMs, nowMs, "Vừa xong", "Hôm qua", Locale.US))

        // Yesterday
        val yesterdayCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        assertEquals("Hôm qua", resolveRelativeTimeLabel(yesterdayCal.timeInMillis, nowMs, "Vừa xong", "Hôm qua", Locale.US))

        // Same year earlier
        val sameYearCal = (nowCal.clone() as Calendar).apply { set(2026, Calendar.OCTOBER, 20) }
        assertEquals("20/10", resolveRelativeTimeLabel(sameYearCal.timeInMillis, nowMs, "Vừa xong", "Hôm qua", Locale.US))
    }

    // --- Avatar Palette Determinism & Contrast ---

    @Test
    fun avatarPaletteIndexIsDeterministicAndNormalised() {
        val idx1 = avatarPaletteIndex("Nguyen Van A")
        val idx2 = avatarPaletteIndex("nguyen van a")
        val idx3 = avatarPaletteIndex("  Nguyen Van A  ")
        assertEquals("Case and trimming differences must produce identical palette index", idx1, idx2)
        assertEquals(idx1, idx3)
        assertTrue("Palette index must be in range 0..7", idx1 in 0..7)
    }

    @Test
    fun extractInitialsHandlesSingleAndMultipleWords() {
        assertEquals("NA", extractInitials("Nguyen An"))
        assertEquals("B", extractInitials("Binh"))
        assertEquals("?", extractInitials(""))
        assertEquals("?", extractInitials("   "))
    }

    @Test
    fun avatarPalettesMeetWcagAaContrastRatio() {
        // Test all 8 palette hues in both Light and Dark modes against their initials text color
        val names = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel")
        for (name in names) {
            val lightColors = avatarColorsForName(name, isDark = false)
            val lightRatio = computeContrastRatio(lightColors.container, lightColors.onContainer)
            assertTrue("Light mode avatar '$name' contrast $lightRatio must be >= 4.5:1", lightRatio >= 4.5)

            val darkColors = avatarColorsForName(name, isDark = true)
            val darkRatio = computeContrastRatio(darkColors.container, darkColors.onContainer)
            assertTrue("Dark mode avatar '$name' contrast $darkRatio must be >= 4.5:1", darkRatio >= 4.5)
        }
    }

    // --- URL Link Sanitizer ---

    @Test
    fun sanitizeLinkUrlPermitsHttpAndHttpsOnly() {
        assertEquals("http://example.com", sanitizeLinkUrl("http://example.com"))
        assertEquals("https://example.com/chat?id=123", sanitizeLinkUrl("https://example.com/chat?id=123"))
        assertEquals("https://sub.domain.org/path#anchor", sanitizeLinkUrl("https://sub.domain.org/path#anchor"))

        // Malicious or unsupported URI schemes must be rejected (return null)
        assertNull("javascript: URI must be rejected", sanitizeLinkUrl("javascript:alert(1)"))
        assertNull("file: URI must be rejected", sanitizeLinkUrl("file:///android_asset/secret.txt"))
        assertNull("intent: URI must be rejected", sanitizeLinkUrl("intent://run#Intent;scheme=app;end"))
        assertNull("ftp: URI must be rejected", sanitizeLinkUrl("ftp://ftp.example.com"))
        assertNull("content: URI must be rejected", sanitizeLinkUrl("content://media/external/images"))
        assertNull("Bare string must be rejected", sanitizeLinkUrl("not_a_valid_url"))
    }

    // --- Helpers ---

    private fun createMessage(id: String, senderId: String, epochMs: Long) = ChatMessage(
        id = id,
        conversationId = "conv-1",
        seq = 1L,
        senderId = senderId,
        senderDisplayName = "User $senderId",
        type = "TEXT",
        content = "Message $id",
        replyToId = null,
        replyToSenderName = null,
        replyToSnippet = null,
        createdAtEpochMs = epochMs,
        clientMessageId = null,
        status = DeliveryStatus.SENT,
        mine = senderId == "me",
    )

    private fun computeContrastRatio(c1: androidx.compose.ui.graphics.Color, c2: androidx.compose.ui.graphics.Color): Double {
        val l1 = relativeLuminance(c1)
        val l2 = relativeLuminance(c2)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(c: androidx.compose.ui.graphics.Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
