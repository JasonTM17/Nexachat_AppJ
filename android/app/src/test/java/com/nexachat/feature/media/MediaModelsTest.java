package com.nexachat.feature.media;

import android.net.Uri;
import com.nexachat.core.common.ApiError;
import java.util.Locale;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

public final class MediaModelsTest {

    private static final ApiError UPLOAD_ERROR = new ApiError.Network("down");

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

    @Test
    public void failedRowKeepsItsErrorAcrossProgressAndStateCopies() {
        PendingAttachment failed = failedRow();

        assertEquals(UPLOAD_ERROR, failed.withProgress(0.6f).getError());
        assertEquals(UPLOAD_ERROR,
                failed.withState(PendingAttachment.PendingState.FAILED).getError());
    }

    @Test
    public void stateChangeAwayFromFailedDropsTheError() {
        assertNull(failedRow()
                .withState(PendingAttachment.PendingState.UPLOADING).getError());
    }

    @Test
    public void progressOnANonFailedRowCannotCarryAStaleError() {
        PendingAttachment row = new PendingAttachment("id-1", mock(Uri.class), "pic.png",
                "image/png", "IMAGE", 0.4f, PendingAttachment.PendingState.UPLOADING,
                UPLOAD_ERROR);

        assertNull(row.withProgress(0.6f).getError());
    }

    private static PendingAttachment failedRow() {
        return new PendingAttachment("id-1", mock(Uri.class), "pic.png", "image/png",
                "IMAGE", 0.4f, PendingAttachment.PendingState.FAILED, UPLOAD_ERROR);
    }
}
