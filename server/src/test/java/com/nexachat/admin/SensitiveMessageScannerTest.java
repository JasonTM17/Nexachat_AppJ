package com.nexachat.admin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyword matches are found in the folded/leet views but reported against the
 * ORIGINAL text: the fold collapses whitespace runs and trims the edges, so a
 * folded offset names the wrong span in the original and the alert snippet
 * brackets the wrong evidence. These cases pin the mapping back.
 */
class SensitiveMessageScannerTest {

    private final SensitiveMessageScanner scanner = new SensitiveMessageScanner();

    private static AlertRuleRow keywordRule(String pattern) {
        return new AlertRuleRow(UUID.randomUUID(), "probe", "SELF_HARM", "HIGH",
                "KEYWORD", pattern, "any", false, true, null, null, null, null);
    }

    @Test
    void keywordOffsetLandsOnTheOriginalPhrase() {
        // Leading spaces and a widened gap shift folded indices away from
        // original indices; the flagged phrase sits at original index 46.
        String content = "    nhắn tin linh tinh        tự tử là điều đáng buồn";
        int phraseAt = content.indexOf("tự tử");

        List<SensitiveMessageScanner.Match> matches =
                scanner.scan(content, List.of(keywordRule("tự tử")));

        assertThat(matches).hasSize(1);
        SensitiveMessageScanner.Match match = matches.get(0);
        assertThat(match.offset()).isEqualTo(phraseAt);
        assertThat(content.substring(match.offset(), match.offset() + match.length()))
                .isEqualTo("tự tử");
    }

    @Test
    void keywordSnippetBracketsTheFlaggedPhrase() {
        String content = "chào bạn   hôm nay      trời đẹp        tự tử không phải lời giải";
        int phraseAt = content.indexOf("tự tử");

        List<SensitiveMessageScanner.Match> matches =
                scanner.scan(content, List.of(keywordRule("tự tử")));

        String snippet = scanner.snippet(content, matches.get(0), 120);
        assertThat(snippet).contains("tự tử");
        assertThat(snippet).doesNotContain("…chào bạn");
        assertThat(matches.get(0).offset()).isEqualTo(phraseAt);
    }

    @Test
    void leetMatchOffsetIsMappedBackToTheOriginalText() {
        String content = "   đừng   s3nd   tiền cho ai đó";

        List<SensitiveMessageScanner.Match> matches =
                scanner.scan(content, List.of(keywordRule("send")));

        assertThat(matches).hasSize(1);
        SensitiveMessageScanner.Match match = matches.get(0);
        assertThat(content.substring(match.offset(), match.offset() + match.length()))
                .isEqualTo("s3nd");
    }

    @Test
    void matchAtTheFoldedEndMapsToTheOriginalEnd() {
        String content = "loạn xa xà      tự   tử";
        int phraseAt = content.indexOf("tự");

        List<SensitiveMessageScanner.Match> matches =
                scanner.scan(content, List.of(keywordRule("tự tử")));

        assertThat(matches).hasSize(1);
        SensitiveMessageScanner.Match match = matches.get(0);
        assertThat(match.offset()).isEqualTo(phraseAt);
        assertThat(content.substring(match.offset(), match.offset() + match.length()))
                .isEqualTo("tự   tử");
    }

    @Test
    void foldViewLengthSpansOriginalWhitespaceRuns() {
        // Folded "tu tu" from original "tự  tử" (two-space run): the offsets
        // carry each folded char's original start plus a trailing sentinel.
        assertThat(new TextNormalizer.FoldedView("tu tu", new int[]{0, 1, 2, 4, 5, 6})
                .originalLength(0, 5))
                .isEqualTo(6);
    }
}
