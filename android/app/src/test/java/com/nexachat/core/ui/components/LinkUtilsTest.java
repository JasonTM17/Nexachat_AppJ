package com.nexachat.core.ui.components;

import kotlin.Pair;
import kotlin.ranges.IntRange;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class LinkUtilsTest {

    @Test
    public void sanitizesOnlyHttpSchemesAndTrimsSurroundingWhitespace() {
        assertEquals("HTTPS://example.test/path?q=hello", LinkUtilsKt.sanitizeLinkUrl("  HTTPS://example.test/path?q=hello  "));
        assertEquals("http://example.test", LinkUtilsKt.sanitizeLinkUrl("http://example.test"));
    }

    @Test
    public void rejectsEmptyMalformedAndNonWebSchemes() {
        assertNull(LinkUtilsKt.sanitizeLinkUrl(""));
        assertNull(LinkUtilsKt.sanitizeLinkUrl("   "));
        assertNull(LinkUtilsKt.sanitizeLinkUrl("javascript:alert(1)"));
        assertNull(LinkUtilsKt.sanitizeLinkUrl("file:///private/document"));
        assertNull(LinkUtilsKt.sanitizeLinkUrl("content://provider/item"));
        assertNull(LinkUtilsKt.sanitizeLinkUrl("https://example.test/has a space"));
    }

    @Test
    public void extractsMultipleLinksWithCorrectUtf16RangesAfterVietnameseTextAndEmoji() {
        String text = "Chào 👋 https://example.test/a và http://nexa.test";
        List<Pair<IntRange, String>> links = LinkUtilsKt.extractHttpLinks(text);

        assertEquals(2, links.size());
        assertEquals("https://example.test/a", links.get(0).getSecond());
        assertEquals(text.indexOf("https://example.test/a"), links.get(0).getFirst().getFirst());
        assertEquals(text.indexOf("https://example.test/a") + "https://example.test/a".length() - 1,
                links.get(0).getFirst().getLast());
        assertEquals("http://nexa.test", links.get(1).getSecond());
        assertEquals(text.indexOf("http://nexa.test"), links.get(1).getFirst().getFirst());
    }

    @Test
    public void blankTextProducesNoLinks() {
        assertTrue(LinkUtilsKt.extractHttpLinks(" \t\n ").isEmpty());
    }
}
