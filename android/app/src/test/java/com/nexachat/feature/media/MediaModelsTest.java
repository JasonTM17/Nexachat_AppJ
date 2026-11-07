package com.nexachat.feature.media;

import java.util.Locale;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MediaModelsTest {

    @Test
    public void formatsByteBoundariesInDeviceLocale() {
        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            assertEquals("0 B", PendingAttachment.formatBytes(0L));
            assertEquals("1023 B", PendingAttachment.formatBytes(1023L));
            assertEquals("1.0 KB", PendingAttachment.formatBytes(1024L));
            assertEquals("1.5 KB", PendingAttachment.formatBytes(1536L));
            assertEquals("1.0 MB", PendingAttachment.formatBytes(1024L * 1024L));
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    @Test
    public void nullAndNegativeSizesHaveNoDisplayValue() {
        assertEquals("", PendingAttachment.formatBytes(null));
        assertEquals("", PendingAttachment.formatBytes(-1L));
    }
}
