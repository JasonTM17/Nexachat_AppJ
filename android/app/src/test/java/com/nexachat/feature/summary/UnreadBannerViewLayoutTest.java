package com.nexachat.feature.summary;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Portrait chat must inflate the unread banner children before ViewBinding binds. */
public final class UnreadBannerViewLayoutTest {

    @Test
    public void unreadBannerViewInflatesIncludeBeforeBind() throws Exception {
        String source = new String(
                Files.readAllBytes(Path.of("src/main/java/com/nexachat/feature/summary/UnreadBannerView.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("IncludeUnreadBannerBinding.inflate"));
        assertFalse(
                "Binding the wrapper root crashes with ClassCastException",
                source.contains("IncludeUnreadBannerBinding.bind(this);"));
    }

    @Test
    public void includeDeclaresDismissControl() throws Exception {
        String layout = new String(
                Files.readAllBytes(Path.of("src/main/res/layout/include_unread_banner.xml")),
                StandardCharsets.UTF_8);
        assertTrue(layout.contains("@+id/dismissUnreadBanner"));
    }
}
