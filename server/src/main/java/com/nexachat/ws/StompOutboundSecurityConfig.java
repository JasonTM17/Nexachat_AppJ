package com.nexachat.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** Registers authenticated-session, recipient and receipt-privacy checks on server-to-client deliveries. */
@Configuration
public class StompOutboundSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private final StompConversationMembershipOutboundInterceptor membershipInterceptor;
    private final StompSeenPrivacyOutboundInterceptor seenPrivacyInterceptor;
    private final StompAuthChannelInterceptor revocationGuard;

    public StompOutboundSecurityConfig(
            StompConversationMembershipOutboundInterceptor membershipInterceptor,
            StompSeenPrivacyOutboundInterceptor seenPrivacyInterceptor,
            StompAuthChannelInterceptor revocationGuard) {
        this.membershipInterceptor = membershipInterceptor;
        this.seenPrivacyInterceptor = seenPrivacyInterceptor;
        this.revocationGuard = revocationGuard;
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(revocationGuard, membershipInterceptor, seenPrivacyInterceptor);
    }
}
