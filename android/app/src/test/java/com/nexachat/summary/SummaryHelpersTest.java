package com.nexachat.summary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

public final class SummaryHelpersTest {
    @Test
    public void unreadBannerUsesTwentyUnreadAndDismissalMarker() {
        assertFalse(UnreadBannerRule.shouldShow(0, null));
        assertFalse(UnreadBannerRule.shouldShow(19, null));
        assertTrue(UnreadBannerRule.shouldShow(20, null));
        assertTrue(UnreadBannerRule.shouldShow(80, null));
        assertFalse(UnreadBannerRule.shouldShow(20, 125L));
    }

    @Test
    public void shareFormatterProducesVietnameseGoldenText() {
        String formatted = SummaryTextFormatter.toShareText(sampleSummary(), new Locale("vi"));

        assertEquals(
                "Tóm tắt cuộc trò chuyện\nKế hoạch đã chốt."
                        + "\n\nĐiểm chính\n• Chốt ngày mai."
                        + "\n\nQuyết định\n• Họp lúc 9:00."
                        + "\n\nViệc cần làm\n• Lan gửi biên bản."
                        + "\n\nCâu hỏi còn mở\n• Ai nhận việc?"
                        + "\n\nChủ đề\n• Lịch họp",
                formatted);
    }

    @Test
    public void shareFormatterProducesEnglishGoldenText() {
        String formatted = SummaryTextFormatter.toShareText(sampleSummary(), Locale.ENGLISH);

        assertEquals(
                "Conversation summary\nKế hoạch đã chốt."
                        + "\n\nKey points\n• Chốt ngày mai."
                        + "\n\nDecisions\n• Họp lúc 9:00."
                        + "\n\nAction items\n• Lan gửi biên bản."
                        + "\n\nOpen questions\n• Ai nhận việc?"
                        + "\n\nTopics\n• Lịch họp",
                formatted);
    }

    @Test
    public void citationResolverReturnsLocalTargetOrUnavailable() {
        assertEquals("message-1", CitationResolver.resolve("message-1", Set.of("message-1")));
        assertEquals(CitationResolver.UNAVAILABLE,
                CitationResolver.resolve("message-2", Set.of("message-1")));
        assertEquals(CitationResolver.UNAVAILABLE, CitationResolver.resolve(null, Set.of("message-1")));
    }

    @Test
    public void scopeLabelMapsToLocalizedResourceKeys() {
        assertEquals("summary_scope_unread", ScopeLabel.resourceName(SummaryScope.UNREAD));
        assertEquals("summary_scope_last_n", ScopeLabel.resourceName(SummaryScope.LAST_N));
        assertEquals("time_today", ScopeLabel.resourceName(SummaryScope.TODAY));
    }

    @Test
    public void shareFormatterUsesResponseLanguageWhenNoLocaleIsGiven() {
        SummaryResponse vietnamese = new SummaryResponse(
                "summary-2", "conversation-1", 1L, 4L, 4, SummaryLanguage.VI,
                "LOCAL", null, null, false, "2026-11-05T10:00:00Z", "  Tóm tắt  ",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertEquals("Tóm tắt cuộc trò chuyện\nTóm tắt",
                SummaryTextFormatter.toShareText(vietnamese, null));
        assertEquals("", SummaryTextFormatter.toShareText(null, Locale.ENGLISH));
    }

    @Test
    public void citationResolverTreatsAMissingLocalSetAsUnavailable() {
        assertEquals(CitationResolver.UNAVAILABLE, CitationResolver.resolve("message-1", null));
    }

    @Test
    public void scopeLabelRejectsAMissingScope() {
        assertThrows(IllegalArgumentException.class, () -> ScopeLabel.resourceName(null));
    }

    private static SummaryResponse sampleSummary() {
        return new SummaryResponse(
                "summary-1", "conversation-1", 1L, 4L, 4, SummaryLanguage.VI,
                "LOCAL", null, null, false, "2026-11-05T10:00:00Z", "Kế hoạch đã chốt.",
                List.of(new SummaryItem("Chốt ngày mai.", List.of("message-1"))),
                List.of(new SummaryItem("Họp lúc 9:00.", List.of("message-2"))),
                List.of(new SummaryActionItem("Lan gửi biên bản.", null, null, List.of("message-3"))),
                List.of(new SummaryItem("Ai nhận việc?", List.of("message-4"))),
                List.of("Lịch họp"), List.of());
    }
}
