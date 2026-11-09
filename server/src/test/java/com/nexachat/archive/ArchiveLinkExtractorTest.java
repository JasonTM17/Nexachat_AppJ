package com.nexachat.archive;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the storage-browser URL rules: http(s) only, punctuation
 * and bracket cleanup, Unicode, the ten-link cap, per-message de-duplication
 * and linear behaviour on huge inputs.
 */
class ArchiveLinkExtractorTest {

    @Test
    void nullAndBlankContentHaveNoLinks() {
        assertThat(ArchiveLinkExtractor.extract(null)).isEmpty();
        assertThat(ArchiveLinkExtractor.extract("")).isEmpty();
        assertThat(ArchiveLinkExtractor.extract("   \n\t ")).isEmpty();
    }

    @Test
    void extractsHttpAndHttpsInFirstOccurrenceOrder() {
        List<String> links = ArchiveLinkExtractor.extract(
                "First https://one.example/a then http://two.example/b and HTTPS://THREE.EXAMPLE/C");

        assertThat(links).containsExactly(
                "https://one.example/a",
                "http://two.example/b",
                "HTTPS://THREE.EXAMPLE/C");
    }

    @Test
    void stripsSentencePunctuationAndEllipsis() {
        List<String> links = ArchiveLinkExtractor.extract(
                "A https://a.example/x. B https://b.example/y, C https://c.example/z; "
                        + "D https://d.example/w! E https://e.example/v? F https://f.example/u... "
                        + "G https://g.example/t: H https://h.example/s'");

        assertThat(links).containsExactly(
                "https://a.example/x",
                "https://b.example/y",
                "https://c.example/z",
                "https://d.example/w",
                "https://e.example/v",
                "https://f.example/u",
                "https://g.example/t",
                "https://h.example/s");
    }

    @Test
    void keepsBalancedParenthesesAndDropsUnbalancedClosers() {
        List<String> links = ArchiveLinkExtractor.extract(
                "See (https://en.wikipedia.org/wiki/Foo_(bar)) and "
                        + "[docs](https://docs.example.com/page). "
                        + "Array https://example.com/a[1] plus stray https://example.com/b)");

        assertThat(links).containsExactly(
                "https://en.wikipedia.org/wiki/Foo_(bar)",
                "https://docs.example.com/page",
                "https://example.com/a[1]",
                "https://example.com/b");
    }

    @Test
    void keepsUnicodeHostsPathsAndQueries() {
        List<String> links = ArchiveLinkExtractor.extract(
                "Xem https://vi.wikipedia.org/wiki/Đường_dẫn?q=tiếng_Việt&x=1#mục-lục "
                        + "và https://münchen.example/straße?q=größe");

        assertThat(links).containsExactly(
                "https://vi.wikipedia.org/wiki/Đường_dẫn?q=tiếng_Việt&x=1#mục-lục",
                "https://münchen.example/straße?q=größe");
    }

    @Test
    void rejectsJavascriptAndDataSchemes() {
        List<String> links = ArchiveLinkExtractor.extract(
                "javascript:alert(1) data:text/html;base64,PGgxPmhpPC9oMT4= "
                        + "ftp://files.example/a mailto:a@example.com");

        assertThat(links).isEmpty();
    }

    @Test
    void rejectsSchemesWithoutAHost() {
        List<String> links = ArchiveLinkExtractor.extract(
                "https:// http:// https://. https://:8080 https:///path");

        assertThat(links).isEmpty();
    }

    @Test
    void doesNotMatchInsideAWord() {
        List<String> links = ArchiveLinkExtractor.extract(
                "abchttps://inside.example https://outside.example");

        assertThat(links).containsExactly("https://outside.example");
    }

    @Test
    void deduplicatesPerMessageAndCapsAtTen() {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            message.append("https://example.com/").append(i).append(' ');
        }
        message.append("https://example.com/0 ");

        List<String> links = ArchiveLinkExtractor.extract(message.toString());

        assertThat(links).hasSize(10);
        assertThat(links).doesNotHaveDuplicates();
        assertThat(links.get(0)).isEqualTo("https://example.com/0");
        assertThat(links.get(9)).isEqualTo("https://example.com/9");
    }

    @Test
    void hugeMessageIsScannedLinearlyAndStillCapped() {
        String huge = "x".repeat(1_000_000)
                + " https://example.com/1 https://example.com/2"
                + " https://example.com/1 https://example.com/3";

        List<String> links = ArchiveLinkExtractor.extract(huge);

        assertThat(links).containsExactly(
                "https://example.com/1",
                "https://example.com/2",
                "https://example.com/3");
    }

    @Test
    void delimitersSplitLinksAcrossLinesAndBrackets() {
        List<String> links = ArchiveLinkExtractor.extract(
                "<https://a.example/1>\n\"https://b.example/2\"\n`https://c.example/3`");

        assertThat(links).containsExactly(
                "https://a.example/1",
                "https://b.example/2",
                "https://c.example/3");
    }
}
