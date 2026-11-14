package com.nexachat.feature.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class MediaViewerChromeTest {

    private static final String FALLBACK = "Attachment";

    @Test
    public void imageShowsSenderWithSentAtSubtitle() {
        MediaViewerChrome chrome = MediaViewerChrome.resolve(
                true, "Binh", "Yesterday", "1000000253.webp", FALLBACK);

        assertEquals("Binh", chrome.getTitle());
        assertEquals("Yesterday", chrome.getSubtitle());
    }

    @Test
    public void visualMediaWithoutSenderOrTimeFallsBackToFileName() {
        MediaViewerChrome chrome = MediaViewerChrome.resolve(
                true, null, null, "photo.webp", FALLBACK);

        assertEquals("photo.webp", chrome.getTitle());
        assertNull(chrome.getSubtitle());
    }

    @Test
    public void visualMediaWithoutSenderKeepsTimeSubtitle() {
        MediaViewerChrome chrome = MediaViewerChrome.resolve(
                true, "  ", "22:24", "photo.webp", FALLBACK);

        assertEquals("photo.webp", chrome.getTitle());
        assertEquals("22:24", chrome.getSubtitle());
    }

    @Test
    public void documentKeepsFileNameAndNoSubtitle() {
        MediaViewerChrome chrome = MediaViewerChrome.resolve(
                false, "Binh", "22:24", "report.pdf", FALLBACK);

        assertEquals("report.pdf", chrome.getTitle());
        assertNull(chrome.getSubtitle());
    }

    @Test
    public void missingEverythingUsesFallbackTitle() {
        MediaViewerChrome chrome = MediaViewerChrome.resolve(
                true, null, null, null, FALLBACK);

        assertEquals(FALLBACK, chrome.getTitle());
        assertNull(chrome.getSubtitle());
    }
}
