package com.nexachat.firebase.config;

import com.nexachat.firebase.auth.FirebaseAdminClient;
import com.nexachat.firebase.auth.FirebaseAdminAuthClient;
import com.nexachat.firebase.auth.FirebaseRealtimeController;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.google.GoogleServiceAccountCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FirebaseRealtimeConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FirebaseRealtimeConfiguration.class, FirebaseRealtimeController.class);

    @Test
    void noFlagOrDatabaseUrlCreatesNoFirebaseBeans() {
        runner.withPropertyValues(
                        "nexachat.firebase.realtime-enabled=false",
                        "nexachat.firebase.database-url=",
                        "nexachat.fcm.service-account-json=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(FirebaseAdminClient.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(FirebaseAdminAuthClient.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(FirebaseRtdbClient.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(GoogleServiceAccountCredentials.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(FirebaseRealtimeController.class)).isEmpty();
                });
    }

    @Test
    void anEmptyDatabaseUrlKeepsFirebaseDisabledEvenIfTheFlagIsSet() {
        runner.withPropertyValues(
                        "nexachat.firebase.realtime-enabled=true",
                        "nexachat.firebase.database-url=",
                        "nexachat.fcm.service-account-json=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(FirebaseAdminClient.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(FirebaseRtdbClient.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(FirebaseRealtimeController.class)).isEmpty();
                });
    }
}
