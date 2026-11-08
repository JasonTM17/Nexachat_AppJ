package com.nexachat.feature.chat.adapter;

import android.view.View;
import com.nexachat.R;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class VoiceBubbleBinderTest {

    @Test
    public void detachedAudioRowKeepsPlayerVisibleUntilItCanResolveOwners() {
        View row = mock(View.class);
        View playerRoot = mock(View.class);
        when(row.findViewById(R.id.voicePlayerRoot)).thenReturn(playerRoot);
        when(row.isAttachedToWindow()).thenReturn(false);

        boolean handled = VoiceBubbleBinder.bindIfVoice(row, audioMessage());

        assertTrue(handled);
        verify(playerRoot).setVisibility(View.VISIBLE);
        verify(row).addOnAttachStateChangeListener(any(View.OnAttachStateChangeListener.class));
    }

    @Test
    public void nullDurationFallsBackToZeroDurationLabel() {
        AttachmentDto attachment = new AttachmentDto(
                "attachment-audio", "voice.m4a", "audio/mp4", 23_730L);

        long durationMs = VoiceBubbleBinder.durationMsOf(attachment);

        assertEquals(0L, durationMs);
        assertEquals("0:00", VoiceBubbleBinder.formatDuration(durationMs));
    }

    private static ChatMessage audioMessage() {
        AttachmentDto attachment = new AttachmentDto(
                "attachment-audio", "voice.m4a", "audio/mp4", 23_730L);
        return new ChatMessage(
                "message-audio", "conversation", 1L, "sender", "Sender",
                MessageDto.TYPE_AUDIO, null, null, null, null, 1_700_000_000_000L,
                null, DeliveryStatus.SENT, true, Collections.singletonList(attachment),
                Collections.emptyList(), null, null);
    }
}
