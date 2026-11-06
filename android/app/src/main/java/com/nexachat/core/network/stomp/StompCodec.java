package com.nexachat.core.network.stomp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.LinkedHashMap;

/** Stateless encoder/decoder for a single STOMP 1.2 frame. */
public final class StompCodec {
    private static final char NUL = '\u0000';

    private StompCodec() {
    }

    @NonNull
    public static String encode(@NonNull StompFrame frame) {
        boolean escapeHeaders = !StompCommand.CONNECT.equals(frame.getCommand())
                && !StompCommand.CONNECTED.equals(frame.getCommand());
        StringBuilder out = new StringBuilder();
        out.append(frame.getCommand()).append('\n');
        for (var entry : frame.getHeaders().entrySet()) {
            out.append(escapeHeaders ? escape(entry.getKey()) : entry.getKey());
            out.append(':');
            out.append(escapeHeaders ? escape(entry.getValue()) : entry.getValue());
            out.append('\n');
        }
        out.append('\n');
        out.append(frame.getBody());
        out.append(NUL);
        return out.toString();
    }

    /**
     * Decodes one complete frame text (terminator already stripped).
     * Returns null for a chunk without a command line. When {@code content-length}
     * is present the body is truncated to it.
     */
    @Nullable
    public static StompFrame decode(@NonNull String frame) {
        int headEnd = frame.length();
        int bodyStart = frame.length();
        int lfSplit = frame.indexOf("\n\n");
        int crlfSplit = frame.indexOf("\r\n\r\n");
        if (lfSplit >= 0 && (crlfSplit < 0 || lfSplit < crlfSplit)) {
            headEnd = lfSplit;
            bodyStart = lfSplit + 2;
        } else if (crlfSplit >= 0) {
            headEnd = crlfSplit;
            bodyStart = crlfSplit + 4;
        }
        String head = frame.substring(0, headEnd);
        String body = bodyStart <= frame.length() ? frame.substring(bodyStart) : "";

        String[] lines = head.split("\n", -1);
        if (lines.length == 0) {
            return null;
        }
        String command = lines[0].trim();
        if (command.isEmpty()) {
            return null;
        }
        boolean unescapeHeaders = !StompCommand.CONNECT.equals(command)
                && !StompCommand.CONNECTED.equals(command);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = removeSuffix(lines[i], "\r");
            if (line.isEmpty()) {
                continue;
            }
            int sep = line.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            String key = line.substring(0, sep);
            String value = line.substring(sep + 1);
            headers.putIfAbsent(
                    unescapeHeaders ? unescape(key) : key,
                    unescapeHeaders ? unescape(value) : value
            );
        }
        String declared = headers.get("content-length");
        Integer length = parseIntOrNull(declared);
        if (length != null && length < body.length()) {
            body = body.substring(0, length);
        }
        return new StompFrame(command, headers, body);
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\r' -> out.append("\\r");
                case '\n' -> out.append("\\n");
                case ':' -> out.append("\\c");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                switch (value.charAt(i + 1)) {
                    case 'r' -> {
                        out.append('\r');
                        i += 2;
                        continue;
                    }
                    case 'n' -> {
                        out.append('\n');
                        i += 2;
                        continue;
                    }
                    case 'c' -> {
                        out.append(':');
                        i += 2;
                        continue;
                    }
                    case '\\' -> {
                        out.append('\\');
                        i += 2;
                        continue;
                    }
                    default -> {
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String removeSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    @Nullable
    private static Integer parseIntOrNull(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
