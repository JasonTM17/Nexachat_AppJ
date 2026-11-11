package com.nexachat.ws;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.stereotype.Component;

/** Serializes raw inbound STOMP handling with guarded server-to-client writes. */
@Component
public class StompSessionWriteLockDecoratorFactory implements WebSocketHandlerDecoratorFactory {

    private final StompRevocationFence revocationFence;

    public StompSessionWriteLockDecoratorFactory(StompRevocationFence revocationFence) {
        this.revocationFence = revocationFence;
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
                try (StompRevocationFence.SessionWriteLease ignored =
                             revocationFence.acquireSessionWrite(session.getId())) {
                    super.handleMessage(session, message);
                }
            }
        };
    }
}
