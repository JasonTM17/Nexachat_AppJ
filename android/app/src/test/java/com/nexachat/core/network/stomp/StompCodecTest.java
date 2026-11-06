package com.nexachat.core.network.stomp;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM coverage for the STOMP 1.2 wire codec: encode produces NUL-terminated
 * frame text; {@link StompParser} incrementally decodes across arbitrary WS
 * message boundaries, with heartbeats skipped.
 */
public final class StompCodecTest {

    @Test
    public void encodeProducesCommandHeadersBlankLineBodyAndNulTerminator() {
        String encoded = StompCodec.encode(frame("{\"a\":1}"));

        assertTrue(encoded.startsWith("SEND\n"));
        assertTrue(encoded.contains("destination:/app/conversations.c-1/messages\n"));
        assertTrue(encoded.contains("content-type:application/json\n"));
        assertTrue(encoded.endsWith("\u0000"));
        // Blank line separates the header block from the body.
        assertTrue(encoded.contains("\n\n{\"a\":1}\u0000"));
    }

    @Test
    public void connectHeadersCarryAcceptVersionHostHeartBeatAndAuthorization() {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("accept-version", "1.2");
        headers.put("host", "10.0.2.2");
        headers.put("heart-beat", "10000,10000");
        headers.put("Authorization", "Bearer eyJhbGciOi");
        String encoded = StompCodec.encode(new StompFrame(StompCommand.CONNECT, headers));

        StompFrame decoded = StompCodec.decode(removeSuffix(encoded, "\u0000"));
        assertNotNull(decoded);
        assertEquals(StompCommand.CONNECT, decoded.getCommand());
        assertEquals("1.2", decoded.getHeaders().get("accept-version"));
        assertEquals("10.0.2.2", decoded.getHeaders().get("host"));
        assertEquals("10000,10000", decoded.getHeaders().get("heart-beat"));
        assertEquals("Bearer eyJhbGciOi", decoded.getHeaders().get("Authorization"));
        assertEquals("", decoded.getBody());
    }

    @Test
    public void decodeParsesServerMessageFrameWithoutContentLength() {
        String wire = "MESSAGE\n" +
            "subscription:sub-1\n" +
            "message-id:m-42\n" +
            "destination:/topic/conversations.c-1\n" +
            "content-type:application/json\n" +
            "\n" +
            "{\"eventType\":\"MESSAGE_CREATED\"}";

        StompFrame decoded = StompCodec.decode(wire);
        assertNotNull(decoded);

        assertEquals(StompCommand.MESSAGE, decoded.getCommand());
        assertEquals("sub-1", decoded.getHeaders().get("subscription"));
        assertEquals("m-42", decoded.getHeaders().get("message-id"));
        assertEquals("/topic/conversations.c-1", decoded.getHeaders().get("destination"));
        assertEquals("{\"eventType\":\"MESSAGE_CREATED\"}", decoded.getBody());
    }

    @Test
    public void headerEscapingRoundTripsColonsNewlinesAndBackslashes() {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("destination", "/app/x");
        headers.put("note", "a:b\\c\nd");
        StompFrame original = frame(headers, "b");
        String encoded = StompCodec.encode(original);

        // STOMP 1.2 escapes live on the wire only in non-CONNECT frames.
        assertTrue(encoded.contains("note:a\\cb\\\\c\\nd\n"));

        StompFrame decoded = StompCodec.decode(removeSuffix(encoded, "\u0000"));
        assertNotNull(decoded);
        assertEquals(original.getHeaders(), decoded.getHeaders());
    }

    @Test
    public void contentLengthHeaderTruncatesBodyToDeclaredLength() {
        String wire = "MESSAGE\ncontent-length:5\n\n12345truncated";

        StompFrame decoded = StompCodec.decode(wire);
        assertNotNull(decoded);

        assertEquals("12345", decoded.getBody());
    }

    @Test
    public void decodeReturnsNullForChunkWithoutCommand() {
        assertNull(StompCodec.decode(""));
        assertNull(StompCodec.decode("   "));
    }

    @Test
    public void parserDrainsTwoFramesConcatenatedInOneMessage() {
        StompParser parser = new StompParser();
        String first = StompCodec.encode(frame("one"));
        String second = StompCodec.encode(frame("two"));

        List<StompFrame> frames = parser.feed(first + second);

        assertEquals(2, frames.size());
        assertEquals("one", frames.get(0).getBody());
        assertEquals("two", frames.get(1).getBody());
    }

    @Test
    public void parserReassemblesFrameSplitAcrossMessages() {
        StompParser parser = new StompParser();
        String wire = StompCodec.encode(frame("hello"));
        int cut = wire.length() / 2;

        assertTrue(parser.feed(wire.substring(0, cut)).isEmpty());
        List<StompFrame> frames = parser.feed(wire.substring(cut));

        assertEquals(1, frames.size());
        assertEquals("hello", frames.get(0).getBody());
    }

    @Test
    public void parserSkipsHeartbeatNewlinesBetweenFrames() {
        StompParser parser = new StompParser();
        String wire = "\n\n" + StompCodec.encode(frame("x")) + "\r\n";

        List<StompFrame> frames = parser.feed(wire);

        assertEquals(1, frames.size());
        assertEquals("x", frames.get(0).getBody());
        assertTrue(parser.feed("\n\n\n").isEmpty());
    }

    @Test
    public void encodedFramesRoundTripThroughParserEndToEnd() {
        StompParser parser = new StompParser();
        LinkedHashMap<String, String> subscribeHeaders = new LinkedHashMap<>();
        subscribeHeaders.put("id", "sub-1");
        subscribeHeaders.put("destination", "/topic/conversations.c-1");
        subscribeHeaders.put("ack", "auto");
        List<StompFrame> toEncode = Arrays.asList(
                frame("{\"clientMessageId\":\"c-1\"}"),
                new StompFrame(StompCommand.SUBSCRIBE, subscribeHeaders)
        );

        StringBuilder wire = new StringBuilder();
        for (StompFrame frame : toEncode) {
            wire.append(StompCodec.encode(frame));
        }

        List<StompFrame> frames = parser.feed(wire.toString());

        assertEquals(toEncode, frames);
    }

    private static StompFrame frame(String body) {
        return frame(StompCommand.SEND, defaultHeaders(), body);
    }

    private static StompFrame frame(LinkedHashMap<String, String> headers, String body) {
        return frame(StompCommand.SEND, headers, body);
    }

    private static StompFrame frame(
            String command,
            LinkedHashMap<String, String> headers,
            String body
    ) {
        return new StompFrame(command, headers, body);
    }

    private static LinkedHashMap<String, String> defaultHeaders() {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("destination", "/app/conversations.c-1/messages");
        headers.put("content-type", "application/json");
        return headers;
    }

    private static String removeSuffix(String value, String suffix) {
        return value.endsWith(suffix)
                ? value.substring(0, value.length() - suffix.length())
                : value;
    }
}
