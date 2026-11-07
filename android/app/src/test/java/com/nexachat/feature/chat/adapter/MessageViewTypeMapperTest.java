package com.nexachat.feature.chat.adapter;

import com.nexachat.R;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MessageViewTypeMapperTest {

    @Test
    public void mapsTextIncomingAndOutgoingLayouts() {
        ChatMessage incoming = message(MessageDto.TYPE_TEXT, "hello", false);
        ChatMessage outgoing = message(MessageDto.TYPE_TEXT, "hi", true);
        assertEquals(R.layout.item_message_text_incoming, MessageViewTypeMapper.layoutResForMessage(incoming));
        assertEquals(R.layout.item_message_text_outgoing, MessageViewTypeMapper.layoutResForMessage(outgoing));
    }

    @Test
    public void callSystemContentUsesSystemLayout() {
        ChatMessage call = message(MessageDto.TYPE_TEXT, "CALL|COMPLETED|10|u1", false);
        assertEquals(MessageContentKind.SYSTEM, MessageViewTypeMapper.resolveContentKind(call));
        assertEquals(R.layout.item_message_system, MessageViewTypeMapper.layoutResForMessage(call));
    }

    @Test
    public void viewTypeRoundTripPreservesKindAndDirection() {
        ChatMessage imageOut = message(MessageDto.TYPE_IMAGE, null, true);
        int viewType = MessageViewTypeMapper.viewTypeForMessage(imageOut);
        assertEquals(R.layout.item_message_image_outgoing, MessageViewTypeMapper.layoutResForViewType(viewType));
    }

    @Test
    public void sameVisualBucketRequiresSameDirectionAndKind() {
        ChatMessage a = message(MessageDto.TYPE_FILE, null, true);
        ChatMessage b = message(MessageDto.TYPE_FILE, null, true);
        ChatMessage c = message(MessageDto.TYPE_TEXT, "x", true);
        assertTrue(MessageViewTypeMapper.sameVisualBucket(a, b));
        assertFalse(MessageViewTypeMapper.sameVisualBucket(a, c));
    }

    private static ChatMessage message(String type, String content, boolean mine) {
        return new ChatMessage(
                "id-" + type + mine,
                "conv",
                1L,
                "sender",
                "Sender",
                type,
                content,
                null,
                null,
                null,
                1_700_000_000_000L,
                null,
                DeliveryStatus.SENT,
                mine,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null
        );
    }
}
