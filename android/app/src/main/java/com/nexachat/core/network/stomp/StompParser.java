package com.nexachat.core.network.stomp;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental STOMP decoder. WebSocket text messages can split a frame or
 * carry several frames. Bare newlines between frames are server heartbeats.
 * One instance per connection.
 */
public final class StompParser {
    private static final char NUL = '\u0000';
    private final StringBuilder buffer = new StringBuilder();

    @NonNull
    public List<StompFrame> feed(@NonNull String text) {
        buffer.append(text);
        List<StompFrame> frames = new ArrayList<>();
        while (true) {
            while (buffer.length() > 0 && (buffer.charAt(0) == '\n' || buffer.charAt(0) == '\r')) {
                buffer.deleteCharAt(0);
            }
            int end = indexOfNul();
            if (end < 0) {
                return frames;
            }
            String chunk = buffer.substring(0, end);
            buffer.delete(0, end + 1);
            if (isBlank(chunk)) {
                continue;
            }
            StompFrame frame = StompCodec.decode(chunk);
            if (frame != null) {
                frames.add(frame);
            }
        }
    }

    public void reset() {
        buffer.setLength(0);
    }

    private int indexOfNul() {
        for (int i = 0; i < buffer.length(); i++) {
            if (buffer.charAt(i) == NUL) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isBlank(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i)) && !Character.isSpaceChar(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
