package com.nexachat.firebase.call;

import com.nexachat.call.CallService;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.firebase.config.FirebaseRealtimeAvailableCondition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Optional call-status adapter, absent unless both Firebase gates are explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@Conditional(FirebaseRealtimeAvailableCondition.class)
@ConditionalOnProperty(name = "nexachat.firebase.call-status-enabled", havingValue = "true")
public class FirebaseCallStatusConfiguration {

    @Bean
    FirebaseCallStatusProjectionService firebaseCallStatusProjectionService(
            FirebaseRtdbClient rtdb,
            CallService callService,
            @Value("${nexachat.calls.ring-timeout-ms:45000}") long ringTimeoutMillis) {
        return new FirebaseCallStatusProjectionService(rtdb, callService, Clock.systemUTC(), ringTimeoutMillis);
    }
}
