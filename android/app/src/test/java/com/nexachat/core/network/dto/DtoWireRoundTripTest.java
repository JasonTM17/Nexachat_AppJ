package com.nexachat.core.network.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.network.stomp.dto.MessageDeletedDto;
import com.nexachat.core.network.stomp.dto.PresenceEventDto;
import com.nexachat.core.network.stomp.dto.ReadUpdateDto;
import com.nexachat.core.network.stomp.dto.SendErrorDto;
import com.nexachat.core.network.stomp.dto.TypingSignalDto;
import com.nexachat.core.network.stomp.dto.WsEnvelope;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Wire shapes from docs/api-contract.md. Reflective Java beans must preserve
 * the expected values when a field is absent.
 */
public final class DtoWireRoundTripTest {
    private final Moshi moshi = new Moshi.Builder().build();

    @Test
    public void messageResponseRoundTripsAndKeepsAbsentReactionsNull() throws Exception {
        String json = "{"
                + "\"id\":\"00000000-0000-4000-8000-000000000007\","
                + "\"conversationId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\","
                + "\"seq\":7,"
                + "\"senderId\":\"11111111-1111-4111-8111-111111111111\","
                + "\"senderDisplayName\":\"Demo\","
                + "\"type\":\"TEXT\","
                + "\"content\":\"Hello from REST\","
                + "\"clientMessageId\":\"3f2b8c1e-9a4d-4e6f-8b7c-1d2e3f4a5b6c\","
                + "\"attachments\":[],"
                + "\"createdAt\":\"2026-11-04T09:10:00Z\","
                + "\"futureField\":true}";
        MessageDto message = moshi.adapter(MessageDto.class).fromJson(json);

        assertEquals("00000000-0000-4000-8000-000000000007", message.getId());
        assertEquals(Long.valueOf(7L), message.getSeq());
        assertEquals("Hello from REST", message.getContent());
        assertEquals(MessageDto.TYPE_TEXT, message.getResolvedType());
        assertNull(message.getReactions());
        assertTrue(message.getAttachments().isEmpty());
        assertNull(message.getEditedAt());
        assertEquals(message, moshi.adapter(MessageDto.class).fromJson(
                moshi.adapter(MessageDto.class).toJson(message)));
    }

    @Test
    public void explicitNullContentAndEmptyReactionsStayDistinct() throws Exception {
        MessageDto tombstone = moshi.adapter(MessageDto.class).fromJson(
                "{\"id\":\"m\",\"content\":null,\"reactions\":[],\"deletedAt\":\"2026-11-04T09:10:00Z\"}");

        assertNull(tombstone.getContent());
        assertTrue(tombstone.getReactions().isEmpty());
        assertEquals("2026-11-04T09:10:00Z", tombstone.getDeletedAt());
    }

    @Test
    public void sendRequestOmitsNullsAndDefaultsMissingType() throws Exception {
        SendMessageRequest request = new SendMessageRequest(
                "3f2b8c1e-9a4d-4e6f-8b7c-1d2e3f4a5b6c",
                MessageDto.TYPE_TEXT,
                "Hello from REST",
                null,
                List.of());
        String json = moshi.adapter(SendMessageRequest.class).toJson(request);
        assertFalse(json.contains("replyToId"));
        assertTrue(json.contains("\"attachmentIds\":[]"));

        SendMessageRequest parsed = moshi.adapter(SendMessageRequest.class).fromJson(
                "{\"clientMessageId\":\"c-1\",\"future\":1}");
        assertEquals(MessageDto.TYPE_TEXT, parsed.getType());
        assertTrue(parsed.getAttachmentIds().isEmpty());
        assertNull(parsed.getContent());
    }

    @Test
    public void authRegisterAndErrorEnvelope() throws Exception {
        RegisterRequest register = new RegisterRequest("+84900000000", "secret-pass", "Demo");
        assertEquals(register, parse(RegisterRequest.class, moshi.adapter(RegisterRequest.class).toJson(register)));

        AuthResponse auth = moshi.adapter(AuthResponse.class).fromJson(
                "{\"user\":{\"id\":\"u\",\"phone\":\"+84900000000\",\"displayName\":\"Demo\"},"
                        + "\"accessToken\":\"a\",\"accessTokenExpiresIn\":900,\"refreshToken\":\"r\","
                        + "\"extra\":0}");
        assertEquals("u", auth.getUser().getId());
        assertNull(auth.getUser().getAvatarUrl());
        assertEquals(900L, auth.getAccessTokenExpiresIn());
        assertEquals("PHONE_EXISTS", ErrorResponse.errorCodeOf(
                "{\"error\":{\"code\":\"PHONE_EXISTS\",\"message\":\"Already registered\"}}"));
    }

    @Test
    public void inboxCursorAndDirectPeerPresence() throws Exception {
        ConversationInboxDto row = moshi.adapter(ConversationInboxDto.class).fromJson(
                "{\"id\":\"conv-1\",\"type\":\"DIRECT\",\"title\":null,"
                        + "\"members\":[{\"userId\":\"peer\",\"displayName\":\"Peer\",\"role\":\"MEMBER\"}],"
                        + "\"peerPresence\":{\"online\":true},"
                        + "\"lastReadSeq\":4,\"unknown\":1}");
        assertEquals(Boolean.TRUE, row.getPeerPresence().getOnline());
        assertNull(row.getPeerPresence().getLastSeenAt());
        assertEquals("peer", row.resolvedOtherId("me"));
        assertEquals(Long.valueOf(4L), row.getLastReadSeq());

        ConversationsPageDto page = moshi.adapter(ConversationsPageDto.class).fromJson("{}");
        assertTrue(page.getItems().isEmpty());
        assertFalse(page.getHasMore());
        assertNull(page.getNextCursor());
    }

    @Test
    public void contactSpellingsAndGroupCreate() throws Exception {
        ContactDto contact = moshi.adapter(ContactDto.class).fromJson("{\"userId\":\"u-2\"}");
        assertEquals("u-2", contact.getResolvedUserId());
        ContactDto other = moshi.adapter(ContactDto.class).fromJson("{\"contactUserId\":\"u-3\"}");
        assertEquals("u-3", other.getResolvedUserId());

        String groupJson = moshi.adapter(CreateGroupRequest.class)
                .toJson(new CreateGroupRequest("Weekend", List.of("u-2")));
        assertEquals(new CreateGroupRequest("Weekend", List.of("u-2")),
                parse(CreateGroupRequest.class, groupJson));

        UpdateConversationRequest patch = moshi.adapter(UpdateConversationRequest.class).fromJson("{}");
        assertNull(patch.getTitle());
        assertNull(patch.getAvatarUrl());
        assertEquals("{\"title\":\"Renamed\"}", moshi.adapter(UpdateConversationRequest.class)
                .toJson(new UpdateConversationRequest("Renamed")));
    }

    @Test
    public void stompEnvelopeReencodesPayloadAndReadWatermark() throws Exception {
        WsEnvelope envelope = moshi.adapter(WsEnvelope.class).fromJson(
                "{\"eventType\":\"MESSAGE_READ\",\"conversationId\":\"c-1\","
                        + "\"serverTs\":\"2026-11-04T09:10:00Z\","
                        + "\"payload\":{\"readerId\":\"u-2\",\"upToSeq\":4},\"extra\":false}");
        assertEquals("MESSAGE_READ", envelope.getEventType());
        String payloadJson = moshi.adapter(Types.newParameterizedType(Map.class, String.class, Object.class))
                .toJson(envelope.getPayload());
        ReadUpdateDto update = moshi.adapter(ReadUpdateDto.class).fromJson(payloadJson);
        assertEquals("u-2", update.getResolvedUserId());
        assertEquals(Long.valueOf(4L), update.getResolvedSeq());

        assertEquals("RATE_LIMITED", moshi.adapter(SendErrorDto.class)
                .fromJson("{\"code\":\"RATE_LIMITED\",\"clientMessageId\":\"c\"}").getCode());
        assertEquals("m-1", moshi.adapter(MessageDeletedDto.class)
                .fromJson("{\"messageId\":\"m-1\",\"deletedAt\":\"2026-11-04T09:10:00Z\"}").getMessageId());
        PresenceEventDto presence = moshi.adapter(PresenceEventDto.class).fromJson("{\"userId\":\"u-2\"}");
        assertNull(presence.getOnline());
        TypingSignalDto typing = parse(TypingSignalDto.class,
                moshi.adapter(TypingSignalDto.class).toJson(new TypingSignalDto("u-2", "Demo")));
        assertEquals("Demo", typing.getDisplayName());
    }

    private <T> T parse(Class<T> type, String json) throws Exception {
        JsonAdapter<T> adapter = moshi.adapter(type);
        T value = adapter.fromJson(json);
        assertEquals(value, adapter.fromJson(adapter.toJson(value)));
        return value;
    }
}
