package com.nexachat.feature.chat.adapter;

import com.nexachat.R;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.AttachmentDtoJsonAdapterFactory;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.util.Collections;
import java.util.List;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class MessageViewTypeMapperTest {

    @Test
    public void serverSystemTypeUsesNeutralSystemLayout() {
        ChatMessage system = message("SYSTEM", "An created the group", true);
        assertTrue(MessageViewTypeMapper.isSystemMessage(system));
        assertEquals(R.layout.item_message_system, MessageViewTypeMapper.layoutResForMessage(system));
    }

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

    @Test
    public void audioTypeWithNullDurationUsesAudioLayout() {
        AttachmentDto attachment = new AttachmentDto("att-audio", "voice.m4a", "audio/mp4", 23_730L);
        ChatMessage audio = message(MessageDto.TYPE_AUDIO, null, true, List.of(attachment));

        assertNull(attachment.getDurationMs());
        assertEquals(MessageContentKind.AUDIO, MessageViewTypeMapper.resolveContentKind(audio));
        assertEquals(R.layout.item_message_audio_outgoing,
                MessageViewTypeMapper.layoutResForMessage(audio));
    }

    @Test
    public void audioMimeAndTypeSurviveServerDtoRoomRoundTrip() throws Exception {
        Moshi moshi = new Moshi.Builder()
                .add(new AttachmentDtoJsonAdapterFactory())
                .build();
        JsonAdapter<MessageDto> messageAdapter = moshi.adapter(MessageDto.class);
        MessageDto server = messageAdapter.fromJson(
                "{\"id\":\"message-audio\",\"seq\":7,\"conversationId\":\"conv\","
                        + "\"senderId\":\"sender\",\"type\":\"AUDIO\",\"attachments\":["
                        + "{\"id\":\"att-audio\",\"originalName\":\"voice.m4a\","
                        + "\"mimeType\":\"audio/mp4\",\"sizeBytes\":23730,\"durationMs\":null}]}");
        assertNotNull(server);
        JsonAdapter<List<AttachmentDto>> attachmentAdapter = moshi.adapter(
                Types.newParameterizedType(List.class, AttachmentDto.class));
        String roomJson = attachmentAdapter.toJson(server.getAttachments());
        List<AttachmentDto> reloadedAttachments = attachmentAdapter.fromJson(roomJson);
        assertNotNull(reloadedAttachments);
        ChatMessage reloaded = message(
                server.getResolvedType(), null, true, reloadedAttachments);

        assertNull(reloadedAttachments.get(0).getDurationMs());
        assertEquals("audio/mp4", reloadedAttachments.get(0).getMimeType());
        assertEquals(MessageContentKind.AUDIO,
                MessageViewTypeMapper.resolveContentKind(reloaded));
        assertEquals(R.layout.item_message_audio_outgoing,
                MessageViewTypeMapper.layoutResForMessage(reloaded));
    }

    private static ChatMessage message(String type, String content, boolean mine) {
        return message(type, content, mine, Collections.emptyList());
    }

    private static ChatMessage message(
            String type, String content, boolean mine, List<AttachmentDto> attachments) {
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
                attachments,
                Collections.emptyList(),
                null,
                null
        );
    }
}
