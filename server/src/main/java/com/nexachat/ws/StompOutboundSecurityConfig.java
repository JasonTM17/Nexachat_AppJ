package com.nexachat.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** Registers authenticated-session and recipient checks on server-to-client deliveries. */
@Configuration
public class StompOutboundSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private final StompConversationMembershipOutboundInterceptor membershipInterceptor;

    public StompOutboundSecurityConfig(
            StompConversationMembershipOutboundInterceptor membershipInterceptor) {
        this.membershipInterceptor = membershipInterceptor;
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(membershipInterceptor);
    }
}
