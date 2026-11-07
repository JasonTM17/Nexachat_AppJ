package com.nexachat.core.ui.components;

import com.nexachat.core.ui.components.LinkUtils.IntRange;
import com.nexachat.core.ui.components.LinkUtils.Pair;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class LinkUtilsTest {

    @Test
    public void sanitizesOnlyHttpSchemesAndTrimsSurroundingWhitespace() {
        assertEquals("HTTPS://example.test/path?q=hello", LinkUtils.sanitizeLinkUrl("  HTTPS://example.test/path?q=hello  "));
        assertEquals("http://example.test", LinkUtils.sanitizeLinkUrl("http://example.test"));
    }

    @Test
    public void sanitizesUrlsWrappedInNbspOrEmSpace() {
        assertEquals("https://example.test", LinkUtils.sanitizeLinkUrl("\u00A0https://example.test\u00A0"));
        assertEquals("https://example.test", LinkUtils.sanitizeLinkUrl("\u2003https://example.test\u2003"));
    }

    @Test
    public void rejectsEmptyMalformedAndNonWebSchemes() {
        assertNull(LinkUtils.sanitizeLinkUrl(""));
        assertNull(LinkUtils.sanitizeLinkUrl("   "));
        assertNull(LinkUtils.sanitizeLinkUrl("javascript:alert(1)"));
        assertNull(LinkUtils.sanitizeLinkUrl("file:///private/document"));
        assertNull(LinkUtils.sanitizeLinkUrl("content://provider/item"));
        assertNull(LinkUtils.sanitizeLinkUrl("https://example.test/has a space"));
    }

    @Test
    public void extractsMultipleLinksWithCorrectUtf16RangesAfterVietnameseTextAndEmoji() {
        String text = "Chào 👋 https://example.test/a và http://nexa.test";
        List<Pair<IntRange, String>> links = LinkUtils.extractHttpLinks(text);

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
        assertTrue(LinkUtils.extractHttpLinks(" \t\n ").isEmpty());
    }
}
