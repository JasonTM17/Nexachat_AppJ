package com.nexachat.feature.inbox;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class InboxPreviewKindTest {

    @Test
    public void recognizesKnownAttachmentMarkers() {
        assertEquals(InboxPreviewKind.PHOTO, InboxPreviewKind.fromContent("IMAGE"));
        assertEquals(InboxPreviewKind.PHOTO, InboxPreviewKind.fromContent("photo|image-1"));
        assertEquals(InboxPreviewKind.VOICE, InboxPreviewKind.fromContent("AUDIO"));
        assertEquals(InboxPreviewKind.FILE, InboxPreviewKind.fromContent("file|report.pdf"));
        assertEquals(InboxPreviewKind.CALL, InboxPreviewKind.fromContent("CALL|OUTGOING|COMPLETED"));
    }

    @Test
    public void leavesOrdinaryTextAndUnrecognizedMarkersUntouched() {
        assertEquals(InboxPreviewKind.TEXT, InboxPreviewKind.fromContent("hello"));
        assertEquals(InboxPreviewKind.TEXT, InboxPreviewKind.fromContent("IMAGE of a sunset"));
        assertEquals(InboxPreviewKind.TEXT, InboxPreviewKind.fromContent(null));
        assertEquals(InboxPreviewKind.TEXT, InboxPreviewKind.fromContent("   "));
    }
}
