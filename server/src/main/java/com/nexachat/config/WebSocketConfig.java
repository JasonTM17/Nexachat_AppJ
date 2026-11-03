package com.nexachat.config;

import com.nexachat.ws.StompAuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over raw WebSocket (plan §11.1/T12):
 *
 * <ul>
 *   <li>Endpoint {@code /ws} WITHOUT SockJS — the client is a native Android
 *       app, no browser fallback transport needed.</li>
 *   <li>{@code enableSimpleBroker("/topic","/queue")}: in-process fan-out;
 *       multi-instance would need a broker relay (documented limitation,
 *       §11.3.7).</li>
 *   <li>{@code setApplicationDestinationPrefixes("/app")}: SEND destinations
 *       routed to {@code @MessageMapping}.</li>
 *   <li>{@code setUserDestinationPrefix("/user")}: {@code /user/queue/inbox}
 *       and {@code /user/queue/errors} resolve per session.</li>
 * </ul>
 *
 * <p>Security lives at the STOMP layer, not the HTTP handshake (plan §13):
 * the HTTP upgrade is permitted through the security chain (see
 * {@code SecurityConfig}), then {@link StompAuthChannelInterceptor} enforces
 * authN on CONNECT and authZ on SUBSCRIBE/SEND — the JWT never travels in a
 * URL query param (access-log leak). Default path matcher stays in effect
 * ({@code AntPathMatcher} with '/' separator — verified for spring-messaging
 * 6.2.x), so {@code /app/conversations/{id}/messages} binds
 * {@code @DestinationVariable} cleanly while {@code /topic/conversations.{id}}
 * stays a literal segment.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authChannelInterceptor;

    public WebSocketConfig(StompAuthChannelInterceptor authChannelInterceptor) {
        this.authChannelInterceptor = authChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry
                .addEndpoint("/ws")
                // Dev-grade: the native client sends no Origin header; keep the
                // same permissive stance as the REST CORS config and restrict
                // per environment before any public deploy.
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
