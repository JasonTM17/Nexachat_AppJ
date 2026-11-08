package com.nexachat.observability;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * Counts STOMP lifecycle events. The events carry the session id, which is
 * never used as a tag.
 */
@Component
public class StompMetricsListener {

    private final ObservabilityMetrics metrics;

    public StompMetricsListener(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @EventListener
    public void onConnect(SessionConnectEvent event) {
        metrics.recordStompEvent("CONNECT");
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        metrics.recordStompEvent("SUBSCRIBE");
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        metrics.recordStompEvent("UNSUBSCRIBE");
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        metrics.recordStompEvent("DISCONNECT");
    }
}
