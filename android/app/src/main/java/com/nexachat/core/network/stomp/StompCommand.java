package com.nexachat.core.network.stomp;

/** STOMP command vocabulary used by this client. */
public final class StompCommand {
    public static final String CONNECT = "CONNECT";
    public static final String CONNECTED = "CONNECTED";
    public static final String SEND = "SEND";
    public static final String SUBSCRIBE = "SUBSCRIBE";
    public static final String UNSUBSCRIBE = "UNSUBSCRIBE";
    public static final String DISCONNECT = "DISCONNECT";
    public static final String MESSAGE = "MESSAGE";
    public static final String ERROR = "ERROR";
    public static final String RECEIPT = "RECEIPT";

    private StompCommand() {
    }
}
