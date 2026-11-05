package com.nexachat.call.core;

import com.squareup.moshi.Moshi;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public final class CallFrameCodecTest {
    private final CallFrameCodec codec = new CallFrameCodec(new Moshi.Builder().build());

    @Test
    public void outgoingSdpUsesTheContractShapeAndRoundTripsFromServerEvent() {
        CallSignal outgoing = new CallSignal(
                CallSignal.Type.SDP,
                "call-1",
                new CallSignal.Sdp("offer", "v=0"),
                null);

        String encoded = codec.encodeSignal(outgoing);
        assertEquals(
                "{\"type\":\"SDP\",\"callId\":\"call-1\","
                        + "\"sdp\":{\"type\":\"offer\",\"description\":\"v=0\"},"
                        + "\"candidate\":null}",
                encoded);

        CallEvent event = codec.decodeEvent(
                "{\"eventType\":\"CALL_SDP\",\"callId\":\"call-1\","
                        + "\"conversationId\":\"conversation-1\","
                        + "\"fromUserId\":\"peer-1\","
                        + "\"serverTs\":\"2026-11-05T00:00:00Z\","
                        + "\"payload\":{\"type\":\"SDP\",\"callId\":\"call-1\","
                        + "\"sdp\":{\"type\":\"answer\",\"description\":\"v=1\"},"
                        + "\"candidate\":null}}");
        assertNotNull(event);
        assertEquals(CallEvent.Type.CALL_SDP, event.getType());
        assertEquals("call-1", event.getCallId());
        assertEquals("conversation-1", event.getConversationId());
        assertEquals("peer-1", event.getFromUserId());
        assertEquals("answer", event.getSignal().getSdp().getType());
        assertEquals("v=1", event.getSignal().getSdp().getDescription());
    }

    @Test
    public void outgoingIceUsesTheContractCandidateShape() {
        CallSignal outgoing = new CallSignal(
                CallSignal.Type.ICE,
                "call-2",
                null,
                new CallSignal.IceCandidate("audio", 0, "candidate:1"));

        assertEquals(
                "{\"type\":\"ICE\",\"callId\":\"call-2\",\"sdp\":null,"
                        + "\"candidate\":{\"sdpMid\":\"audio\",\"sdpMLineIndex\":0,"
                        + "\"candidate\":\"candidate:1\"}}",
                codec.encodeSignal(outgoing));

        CallEvent event = codec.decodeEvent(
                "{\"eventType\":\"CALL_ICE\",\"callId\":\"call-2\","
                        + "\"conversationId\":\"conversation-1\",\"fromUserId\":\"peer-1\","
                        + "\"serverTs\":\"2026-11-05T00:00:00Z\","
                        + "\"payload\":{\"type\":\"ICE\",\"callId\":\"call-2\","
                        + "\"sdp\":null,\"candidate\":{\"sdpMid\":\"audio\","
                        + "\"sdpMLineIndex\":0,\"candidate\":\"candidate:1\"}}}");
        assertNotNull(event);
        assertEquals(Integer.valueOf(0), event.getSignal().getCandidate().getSdpMLineIndex());
        assertEquals("candidate:1", event.getSignal().getCandidate().getCandidate());
    }

    @Test
    public void controlFrameKeepsNullPayloadFields() {
        assertEquals(
                "{\"type\":\"INVITE\",\"callId\":\"call-3\",\"sdp\":null,\"candidate\":null}",
                codec.encodeSignal(CallSignal.control(CallSignal.Type.INVITE, "call-3")));
    }

    @Test
    public void oversizedSdpAndCandidateAreRejectedBeforeSend() {
        String sdp = repeat('s', 16 * 1024 + 1);
        String candidate = repeat('c', 1025);
        assertNull(codec.encodeSignal(new CallSignal(
                CallSignal.Type.SDP, "call-1", new CallSignal.Sdp("offer", sdp), null)));
        assertNull(codec.encodeSignal(new CallSignal(
                CallSignal.Type.ICE, "call-1", null,
                new CallSignal.IceCandidate("audio", 0, candidate))));
    }

    @Test
    public void signalTypesRequireTheirMatchingPayloadOnly() {
        assertNull(codec.encodeSignal(new CallSignal(CallSignal.Type.SDP, "call-1", null, null)));
        assertNull(codec.encodeSignal(new CallSignal(
                CallSignal.Type.INVITE,
                "call-1",
                new CallSignal.Sdp("offer", "v=0"),
                null)));
    }

    @Test
    public void uncorrelatedSharedQueueErrorIsNotTreatedAsCallFailure() {
        assertNull(codec.decodeError("{\"code\":\"VALIDATION_FAILED\"}"));
        CallEvent error = codec.decodeError("{\"code\":\"CALL_STATE_INVALID\",\"callId\":\"call-1\"}");
        assertNotNull(error);
        assertEquals("CALL_STATE_INVALID", error.getErrorCode());
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
