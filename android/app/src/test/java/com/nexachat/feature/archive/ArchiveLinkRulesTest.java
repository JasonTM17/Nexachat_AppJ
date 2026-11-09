package com.nexachat.feature.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ArchiveLinkRulesTest {

    @Test
    public void allowsOnlyWellFormedHttpsUrlsForBrowserActions() {
        assertEquals("https://example.test/path?q=one", ArchiveLinkRules.safeHttpsUrl(
                "  https://example.test/path?q=one  "));
        assertNull(ArchiveLinkRules.safeHttpsUrl("http://example.test"));
        assertNull(ArchiveLinkRules.safeHttpsUrl("javascript:alert(1)"));
        assertNull(ArchiveLinkRules.safeHttpsUrl("intent://example.test/#Intent;end"));
        assertNull(ArchiveLinkRules.safeHttpsUrl("file:///etc/passwd"));
        assertNull(ArchiveLinkRules.safeHttpsUrl("https:example.test"));
        assertNull(ArchiveLinkRules.safeHttpsUrl("https://user@example.test/path"));
        assertNull(ArchiveLinkRules.safeHttpsUrl("https://bad host.test"));
        assertNull(ArchiveLinkRules.safeHttpsUrl(null));
    }
}
