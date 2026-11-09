package com.nexachat.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** Registers authenticated-session, recipient and receipt-privacy checks on server-to-client deliveries. */
@Configuration
public class StompOutboundSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private final StompConversationMembershipOutboundInterceptor membershipInterceptor;
    private final StompSeenPrivacyOutboundInterceptor seenPrivacyInterceptor;

    public StompOutboundSecurityConfig(
            StompConversationMembershipOutboundInterceptor membershipInterceptor,
            StompSeenPrivacyOutboundInterceptor seenPrivacyInterceptor) {
        this.membershipInterceptor = membershipInterceptor;
        this.seenPrivacyInterceptor = seenPrivacyInterceptor;
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(membershipInterceptor, seenPrivacyInterceptor);
    }
}
