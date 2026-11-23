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
        // Per-recipient delivery frames run blocking membership/privacy
        // probes; the default pool (2xCPU = 2 threads on Render Free)
        // serializes fan-out. A small pool overlaps the Neon waits; the
        // bounded queue keeps overload backpressured rather than queued
        // unboundedly.
        registration.taskExecutor().corePoolSize(4).maxPoolSize(8).queueCapacity(1000);
        registration.interceptors(revocationGuard, membershipInterceptor, seenPrivacyInterceptor);
    }
}
