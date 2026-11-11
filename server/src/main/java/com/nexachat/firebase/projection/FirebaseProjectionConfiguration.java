package com.nexachat.firebase.projection;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.firebase.auth.FirebaseBridgeLock;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.firebase.config.FirebaseRealtimeAvailableCondition;
import com.nexachat.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;

import java.time.Clock;

/** Projection jobs and hooks are absent unless the S1 Firebase bridge is opted in. */
@Configuration(proxyBeanMethods = false)
@Conditional(FirebaseRealtimeAvailableCondition.class)
public class FirebaseProjectionConfiguration {

    @Bean
    FirebaseProjectionMetrics firebaseProjectionMetrics(MeterRegistry registry) {
        return new FirebaseProjectionMetrics(registry);
    }

    @Bean
    FirebaseAuthorizationProjectionService firebaseAuthorizationProjectionService(
            UserRepository users,
            ConversationRepository conversations,
            ConversationMemberRepository members,
            FirebaseRtdbClient rtdb,
            FirebaseBridgeLock locks,
            FirebaseProjectionMetrics metrics) {
        return new FirebaseAuthorizationProjectionService(users, conversations, members, rtdb, locks, metrics);
    }

    @Bean
    FirebaseProjectionRetryQueue firebaseProjectionRetryQueue(FirebaseProjectionMetrics metrics) {
        return new FirebaseProjectionRetryQueue(Clock.systemUTC(), metrics);
    }

    @Bean
    FirebaseAuthorizationProjectionListener firebaseAuthorizationProjectionListener(
            FirebaseAuthorizationProjectionService projections,
            FirebaseProjectionRetryQueue retries) {
        return new FirebaseAuthorizationProjectionListener(projections, retries);
    }

    @Bean
    FirebaseAuthorizationBackfillJob firebaseAuthorizationBackfillJob(
            UserRepository users,
            ConversationRepository conversations,
            FirebaseAuthorizationProjectionService projections,
            FirebaseRtdbClient rtdb,
            FirebaseProjectionMetrics metrics) {
        return new FirebaseAuthorizationBackfillJob(users, conversations, projections, rtdb, metrics);
    }

    @Bean
    FirebaseAuthorizationLeaseSweepJob firebaseAuthorizationLeaseSweepJob(
            UserRepository users,
            ConversationRepository conversations,
            FirebaseRtdbClient rtdb,
            FirebaseProjectionMetrics metrics) {
        return new FirebaseAuthorizationLeaseSweepJob(users, conversations, rtdb, Clock.systemUTC(), metrics);
    }
}
