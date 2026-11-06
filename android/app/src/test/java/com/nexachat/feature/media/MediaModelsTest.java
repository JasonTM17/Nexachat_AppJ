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
            assertEquals("0 B", MediaModelsKt.formatBytes(0L));
            assertEquals("1023 B", MediaModelsKt.formatBytes(1023L));
            assertEquals("1.0 KB", MediaModelsKt.formatBytes(1024L));
            assertEquals("1.5 KB", MediaModelsKt.formatBytes(1536L));
            assertEquals("1.0 MB", MediaModelsKt.formatBytes(1024L * 1024L));
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    @Test
    public void nullAndNegativeSizesHaveNoDisplayValue() {
        assertEquals("", MediaModelsKt.formatBytes(null));
        assertEquals("", MediaModelsKt.formatBytes(-1L));
    }
}
