package com.nexachat.core.network.stomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the STOMP 1.2 wire codec (plan §11.1):
 * encode → NUL-terminated frame text; [StompParser] → incremental decode
 * across arbitrary WS message boundaries, with heartbeats skipped.
 */
class StompCodecTest {

    private fun frame(
        command: String = StompCommand.SEND,
        headers: LinkedHashMap<String, String> = linkedMapOf(
            "destination" to "/app/conversations.c-1/messages",
            "content-type" to "application/json",
        ),
        body: String = "{}",
    ) = StompFrame(command, headers, body)

    @Test
    fun `encode produces command headers blank line body and NUL terminator`() {
        val encoded = StompCodec.encode(frame(body = "{\"a\":1}"))

        assertTrue(encoded.startsWith("SEND\n"))
        assertTrue(encoded.contains("destination:/app/conversations.c-1/messages\n"))
        assertTrue(encoded.contains("content-type:application/json\n"))
        assertTrue(encoded.endsWith("\u0000"))
        // Blank line separates the header block from the body.
        assertTrue(encoded.contains("\n\n{\"a\":1}\u0000"))
    }

    @Test
    fun `CONNECT headers carry accept-version host heart-beat and Authorization`() {
        val encoded = StompCodec.encode(
            StompFrame(
                command = StompCommand.CONNECT,
                headers = linkedMapOf(
                    "accept-version" to "1.2",
                    "host" to "10.0.2.2",
                    "heart-beat" to "10000,10000",
                    "Authorization" to "Bearer eyJhbGciOi",
                ),
            ),
        )

        val decoded = StompCodec.decode(encoded.removeSuffix("\u0000"))!!
        assertEquals(StompCommand.CONNECT, decoded.command)
        assertEquals("1.2", decoded.headers["accept-version"])
        assertEquals("10.0.2.2", decoded.headers["host"])
        assertEquals("10000,10000", decoded.headers["heart-beat"])
        assertEquals("Bearer eyJhbGciOi", decoded.headers["Authorization"])
        assertEquals("", decoded.body)
    }

    @Test
    fun `decode parses a server MESSAGE frame without content-length`() {
        val wire = "MESSAGE\n" +
            "subscription:sub-1\n" +
            "message-id:m-42\n" +
            "destination:/topic/conversations.c-1\n" +
            "content-type:application/json\n" +
            "\n" +
            "{\"eventType\":\"MESSAGE_CREATED\"}"

        val decoded = StompCodec.decode(wire)!!

        assertEquals(StompCommand.MESSAGE, decoded.command)
        assertEquals("sub-1", decoded.headers["subscription"])
        assertEquals("m-42", decoded.headers["message-id"])
        assertEquals("/topic/conversations.c-1", decoded.headers["destination"])
        assertEquals("{\"eventType\":\"MESSAGE_CREATED\"}", decoded.body)
    }

    @Test
    fun `header escaping round-trips colons newlines and backslashes`() {
        val original = frame(
            headers = linkedMapOf("destination" to "/app/x", "note" to "a:b\\c\nd"),
            body = "b",
        )
        val encoded = StompCodec.encode(original)

        // STOMP 1.2 escapes live on the wire only in non-CONNECT frames.
        assertTrue(encoded.contains("note:a\\cb\\\\c\\nd\n"))

        val decoded = StompCodec.decode(encoded.removeSuffix("\u0000"))!!
        assertEquals(original.headers, decoded.headers)
    }

    @Test
    fun `content-length header truncates the body to the declared length`() {
        val wire = "MESSAGE\ncontent-length:5\n\n12345truncated"

        val decoded = StompCodec.decode(wire)!!

        assertEquals("12345", decoded.body)
    }

    @Test
    fun `decode returns null for a chunk without a command`() {
        assertNull(StompCodec.decode(""))
        assertNull(StompCodec.decode("   "))
    }

    @Test
    fun `parser drains two frames concatenated in one message`() {
        val parser = StompParser()
        val first = StompCodec.encode(frame(body = "one"))
        val second = StompCodec.encode(frame(body = "two"))

        val frames = parser.feed(first + second)

        assertEquals(2, frames.size)
        assertEquals("one", frames[0].body)
        assertEquals("two", frames[1].body)
    }

    @Test
    fun `parser reassembles a frame split across messages`() {
        val parser = StompParser()
        val wire = StompCodec.encode(frame(body = "hello"))
        val cut = wire.length / 2

        assertTrue(parser.feed(wire.substring(0, cut)).isEmpty())
        val frames = parser.feed(wire.substring(cut))

        assertEquals(1, frames.size)
        assertEquals("hello", frames.single().body)
    }

    @Test
    fun `parser skips heartbeat newlines between frames`() {
        val parser = StompParser()
        val wire = "\n\n" + StompCodec.encode(frame(body = "x")) + "\r\n"

        val frames = parser.feed(wire)

        assertEquals(1, frames.size)
        assertEquals("x", frames.single().body)
        assertTrue(parser.feed("\n\n\n").isEmpty())
    }

    @Test
    fun `encoded frames round-trip through the parser end to end`() {
        val parser = StompParser()
        val toEncode = listOf(
            frame(body = "{\"clientMessageId\":\"c-1\"}"),
            StompFrame(
                command = StompCommand.SUBSCRIBE,
                headers = linkedMapOf(
                    "id" to "sub-1",
                    "destination" to "/topic/conversations.c-1",
                    "ack" to "auto",
                ),
            ),
        )

        val frames = parser.feed(toEncode.joinToString("") { StompCodec.encode(it) })

        assertEquals(toEncode, frames)
    }
}
