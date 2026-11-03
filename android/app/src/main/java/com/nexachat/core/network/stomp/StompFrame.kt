package com.nexachat.core.network.stomp

/**
 * STOMP 1.2 frame model and wire codec (plan §11.1 — raw WebSocket, no SockJS).
 *
 * Wire layout of one frame:
 *   COMMAND  (single line)
 *   header:value  (zero or more lines, first value wins on repeated keys)
 *   <empty line>
 *   body
 *   NUL terminator
 *
 * Server heartbeats arrive as bare newline characters between frames and are
 * skipped by [StompParser]. Header escaping follows STOMP 1.2: backslash,
 * carriage return, line feed and colon are escaped in every frame EXCEPT
 * CONNECT/CONNECTED (those two predate the escaping rule).
 */
data class StompFrame(
    val command: String,
    val headers: LinkedHashMap<String, String> = linkedMapOf(),
    val body: String = "",
)

/** STOMP command vocabulary used by this client (plan §11.2). */
object StompCommand {
    const val CONNECT = "CONNECT"
    const val CONNECTED = "CONNECTED"
    const val SEND = "SEND"
    const val SUBSCRIBE = "SUBSCRIBE"
    const val UNSUBSCRIBE = "UNSUBSCRIBE"
    const val DISCONNECT = "DISCONNECT"
    const val MESSAGE = "MESSAGE"
    const val ERROR = "ERROR"
    const val RECEIPT = "RECEIPT"
}

/** Stateless encoder/decoder for single frames. */
object StompCodec {

    private const val NUL = '\u0000'

    fun encode(frame: StompFrame): String {
        val escapeHeaders = frame.command != StompCommand.CONNECT &&
            frame.command != StompCommand.CONNECTED
        return buildString {
            append(frame.command).append('\n')
            for ((key, value) in frame.headers) {
                append(if (escapeHeaders) escape(key) else key)
                append(':')
                append(if (escapeHeaders) escape(value) else value)
                append('\n')
            }
            append('\n')
            append(frame.body)
            append(NUL)
        }
    }

    /**
     * Decodes one complete frame text (terminator already stripped).
     * Returns null for a chunk without a command line so callers can skip
     * stray separators instead of crashing. When a `content-length` header is
     * present the body is truncated to it; when absent (our server contract)
     * the body is everything up to the NUL terminator.
     */
    fun decode(frame: String): StompFrame? {
        var headEnd = frame.length
        var bodyStart = frame.length
        val lfSplit = frame.indexOf("\n\n")
        val crlfSplit = frame.indexOf("\r\n\r\n")
        when {
            lfSplit >= 0 && (crlfSplit < 0 || lfSplit < crlfSplit) -> {
                headEnd = lfSplit
                bodyStart = lfSplit + 2
            }
            crlfSplit >= 0 -> {
                headEnd = crlfSplit
                bodyStart = crlfSplit + 4
            }
        }
        val head = frame.substring(0, headEnd)
        var body = if (bodyStart <= frame.length) frame.substring(bodyStart) else ""

        val lines = head.split('\n')
        val command = lines.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val unescapeHeaders = command != StompCommand.CONNECT &&
            command != StompCommand.CONNECTED
        val headers = LinkedHashMap<String, String>()
        for (rawLine in lines.drop(1)) {
            val line = rawLine.removeSuffix("\r")
            if (line.isEmpty()) continue
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            val key = line.substring(0, sep)
            val value = line.substring(sep + 1)
            // First value wins on repeated header keys (STOMP 1.2 spec).
            headers.putIfAbsent(
                if (unescapeHeaders) unescape(key) else key,
                if (unescapeHeaders) unescape(value) else value,
            )
        }
        headers["content-length"]?.toIntOrNull()?.let { declared ->
            // Declared in octets on the wire; OkHttp already decoded UTF-8 so
            // this is exact for ASCII bodies and a safe bound otherwise.
            if (declared < body.length) body = body.substring(0, declared)
        }
        return StompFrame(command, headers, body)
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                ':' -> append("\\c")
                else -> append(c)
            }
        }
    }

    private fun unescape(value: String): String {
        if (value.indexOf('\\') < 0) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    'r' -> { out.append('\r'); i += 2; continue }
                    'n' -> { out.append('\n'); i += 2; continue }
                    'c' -> { out.append(':'); i += 2; continue }
                    '\\' -> { out.append('\\'); i += 2; continue }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}

/**
 * Incremental STOMP decoder. WS text messages can split a frame anywhere or
 * carry several frames at once, so input is buffered and complete frames are
 * drained on each [feed]. Bare newlines between frames are server heartbeats
 * and skipped. One instance per physical connection — reset on reconnect by
 * constructing a new parser.
 */
class StompParser {

    private val buffer = StringBuilder()

    fun feed(text: String): List<StompFrame> {
        buffer.append(text)
        val frames = mutableListOf<StompFrame>()
        while (true) {
            // Drop leading EOLs (heartbeats and inter-frame separators).
            while (buffer.isNotEmpty() && (buffer[0] == '\n' || buffer[0] == '\r')) {
                buffer.deleteCharAt(0)
            }
            val end = buffer.indexOf(NUL)
            if (end < 0) return frames
            val chunk = buffer.substring(0, end)
            buffer.delete(0, end + 1)
            if (chunk.isBlank()) continue
            StompCodec.decode(chunk)?.let { frames += it }
        }
    }

    fun reset() {
        buffer.setLength(0)
    }

    private companion object {
        const val NUL = '\u0000'
    }
}
