package com.nexachat.config;

import com.nexachat.ws.StompAuthChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
    private final SecurityProperties securityProperties;

    public WebSocketConfig(StompAuthChannelInterceptor authChannelInterceptor,
                           SecurityProperties securityProperties) {
        this.authChannelInterceptor = authChannelInterceptor;
        this.securityProperties = securityProperties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry
                .addEndpoint("/ws")
                // Same per-environment allow-list as REST CORS. A native client
                // sends no Origin header, which the handshake interceptor admits
                // regardless; a browser origin outside the list is rejected.
                .setAllowedOrigins(securityProperties.corsAllowedOrigins().toArray(new String[0]));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // The SimpleBroker only answers the client heart-beat header when it has a
        // scheduler; without one the server advertises 0,0 and an idle socket
        // (e.g. a ringing caller) trips the client's 30 s liveness guard.
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {10_000, 10_000})
                .setTaskScheduler(brokerHeartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    private ThreadPoolTaskScheduler brokerHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
