package com.nexachat.core.network.dto;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AttachmentDtoHelpersTest {

    @Test
    public void mapsSupportedMimeFamiliesToWireMessageTypes() {
        assertEquals(MessageDto.TYPE_IMAGE, AttachmentDto.messageTypeForMime("image/jpeg"));
        assertEquals(MessageDto.TYPE_VIDEO, AttachmentDto.messageTypeForMime("video/mp4"));
        assertEquals(MessageDto.TYPE_AUDIO, AttachmentDto.messageTypeForMime("audio/ogg"));
    }

    @Test
    public void unknownAndMissingMimeTypesUseFileMessageType() {
        assertEquals(MessageDto.TYPE_FILE, AttachmentDto.messageTypeForMime("application/pdf"));
        assertEquals(MessageDto.TYPE_FILE, AttachmentDto.messageTypeForMime(null));
    }

    @Test
    public void attachmentTypeFlagsAgreeWithTheWireType() {
        AttachmentDto image = new AttachmentDto(null, null, null, "image/png", null, null, null, null, null);
        AttachmentDto audio = new AttachmentDto(null, null, null, "audio/mpeg", null, null, null, null, null);

        assertTrue(image.isImage());
        assertFalse(image.isVideo());
        assertFalse(image.isAudio());
        assertEquals(MessageDto.TYPE_IMAGE, image.getMessageType());
        assertTrue(audio.isAudio());
        assertEquals(MessageDto.TYPE_AUDIO, audio.getMessageType());
    }
}
