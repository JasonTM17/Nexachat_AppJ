package com.nexachat.core.network.stomp;

import androidx.annotation.NonNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * STOMP 1.2 frame. Header order is significant on the wire, so headers stay a
 * {@link LinkedHashMap}. The first value wins when a key is repeated.
 */
public final class StompFrame {
    private final String command;
    private final LinkedHashMap<String, String> headers;
    private final String body;

    public StompFrame(@NonNull String command) {
        this(command, new LinkedHashMap<>(), "");
    }

    public StompFrame(@NonNull String command, @NonNull LinkedHashMap<String, String> headers) {
        this(command, headers, "");
    }

    public StompFrame(
            @NonNull String command,
            @NonNull LinkedHashMap<String, String> headers,
            @NonNull String body
    ) {
        this.command = command;
        this.headers = headers;
        this.body = body;
    }

    public StompFrame(
            @NonNull String command,
            @NonNull Map<String, String> headers,
            @NonNull String body
    ) {
        this(command, new LinkedHashMap<>(headers), body);
    }

    @NonNull
    public String getCommand() {
        return command;
    }

    @NonNull
    public LinkedHashMap<String, String> getHeaders() {
        return headers;
    }

    @NonNull
    public String getBody() {
        return body;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof StompFrame frame)) {
            return false;
        }
        return command.equals(frame.command)
                && headers.equals(frame.headers)
                && body.equals(frame.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(command, headers, body);
    }
}
