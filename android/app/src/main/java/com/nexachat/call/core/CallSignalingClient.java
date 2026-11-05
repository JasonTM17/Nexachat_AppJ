package com.nexachat.call.core;

/** Authenticated STOMP transport for call events and signal frames. */
public interface CallSignalingClient {
    interface Listener {
        void onEvent(CallEvent event);
        void onDisconnected();
    }

    void start(Listener listener);
    void stop();
    boolean send(String conversationId, CallSignal signal);
    boolean isConnected();
}
