package com.nexachat.call.core;

/** Keeps the call and shared error queues tied to each authenticated STOMP open. */
final class CallQueueSubscriptions {
    interface Transport {
        void subscribe(String destination);
        void unsubscribe(String destination);
    }

    static final String CALLS_DESTINATION = "/user/queue/calls";

    private final Transport transport;
    private boolean started;
    private boolean open;

    CallQueueSubscriptions(Transport transport) {
        this.transport = transport;
    }

    synchronized void start(boolean currentlyOpen) {
        started = true;
        if (currentlyOpen) onOpen();
    }

    synchronized void onOpen() {
        if (!started || open) return;
        open = true;
        transport.subscribe(CALLS_DESTINATION);
    }

    synchronized void onClosed() {
        open = false;
    }

    synchronized void stop() {
        if (!started) return;
        started = false;
        open = false;
        transport.unsubscribe(CALLS_DESTINATION);
    }
}
