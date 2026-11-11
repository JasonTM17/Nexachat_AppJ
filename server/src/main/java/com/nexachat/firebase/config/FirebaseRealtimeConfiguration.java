package com.nexachat.firebase.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.firebase.auth.FirebaseAdminAuthClient;
import com.nexachat.firebase.auth.FirebaseBridgeLock;
import com.nexachat.firebase.auth.FirebaseRealtimeAuthService;
import com.nexachat.firebase.auth.FirebaseSessionRevocationListener;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.firebase.client.FirebaseRtdbRestClient;
import com.nexachat.firebase.projection.FirebaseProjectionRetryQueue;
import com.nexachat.google.GoogleServiceAccountCredentials;
import com.nexachat.google.GoogleServiceAccountCredentialsFactory;
import com.nexachat.notification.FcmProperties;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;

import java.time.Clock;
import java.time.Duration;

/** Opt-in beans for the server-owned Firebase Auth and RTDB bridge. */
@Configuration(proxyBeanMethods = false)
@Conditional(FirebaseRealtimeAvailableCondition.class)
public class FirebaseRealtimeConfiguration {

    @Bean
    FirebaseDatabaseUrl firebaseDatabaseUrl(@Value("${nexachat.firebase.database-url}") String configured) {
        return FirebaseDatabaseUrl.parse(configured);
    }

    @Bean
    GoogleServiceAccountCredentials firebaseGoogleServiceAccountCredentials(
            FcmProperties properties,
            ObjectMapper objectMapper) {
        String projectId = properties.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("FCM_PROJECT_ID is required when Firebase realtime is enabled");
        }
        GoogleServiceAccountCredentials credentials = GoogleServiceAccountCredentialsFactory.load(
                properties.getServiceAccountJson(), objectMapper);
        if (credentials.projectId() != null && !projectId.equals(credentials.projectId())) {
            throw new IllegalStateException("FCM_PROJECT_ID does not match the service-account project");
        }
        return credentials;
    }

    @Bean(destroyMethod = "close")
    FirebaseAdminAuthClient firebaseAdminAuthClient(GoogleServiceAccountCredentials credentials,
                                                    FcmProperties properties,
                                                    FirebaseDatabaseUrl databaseUrl,
                                                    @Value("${nexachat.firebase.connect-timeout-seconds:5}") int connectTimeoutSeconds,
                                                    @Value("${nexachat.firebase.read-timeout-seconds:10}") int readTimeoutSeconds) {
        return new FirebaseAdminAuthClient(
                credentials.googleCredentials(),
                properties.getProjectId(),
                databaseUrl.value(),
                timeoutMillis(connectTimeoutSeconds),
                timeoutMillis(readTimeoutSeconds));
    }

    @Bean
    FirebaseRtdbClient firebaseRtdbClient(FirebaseDatabaseUrl databaseUrl,
                                         GoogleServiceAccountCredentials credentials,
                                         ObjectMapper objectMapper,
                                         @Value("${nexachat.firebase.connect-timeout-seconds:5}") int connectTimeoutSeconds,
                                         @Value("${nexachat.firebase.read-timeout-seconds:10}") int readTimeoutSeconds) {
        return new FirebaseRtdbRestClient(
                databaseUrl,
                credentials.googleCredentials(),
                objectMapper,
                Duration.ofSeconds(connectTimeoutSeconds),
                Duration.ofSeconds(readTimeoutSeconds));
    }

    @Bean
    FirebaseBridgeLock firebaseBridgeLock() {
        return new FirebaseBridgeLock();
    }

    @Bean
    FirebaseRealtimeAuthService firebaseRealtimeAuthService(UserRepository users,
                                                            ConversationMemberRepository members,
                                                            FirebaseAdminAuthClient admin,
                                                            FirebaseRtdbClient rtdb,
                                                            FirebaseBridgeLock locks,
                                                            FirebaseDatabaseUrl databaseUrl) {
        return new FirebaseRealtimeAuthService(users, members, admin, rtdb, locks, databaseUrl, Clock.systemUTC());
    }

    @Bean
    FirebaseSessionRevocationListener firebaseSessionRevocationListener(FirebaseBridgeLock locks,
                                                                        FirebaseRtdbClient rtdb,
                                                                        FirebaseAdminAuthClient admin,
                                                                        ConversationMemberRepository members,
                                                                        FirebaseProjectionRetryQueue retries) {
        return new FirebaseSessionRevocationListener(locks, rtdb, admin, members, retries);
    }

    private static int timeoutMillis(int seconds) {
        if (seconds <= 0) {
            throw new IllegalStateException("Firebase request timeouts must be positive");
        }
        return Math.multiplyExact(seconds, 1_000);
    }
}
