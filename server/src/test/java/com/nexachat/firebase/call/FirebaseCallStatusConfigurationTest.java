package com.nexachat.firebase.call;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FirebaseCallStatusConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FirebaseCallStatusConfiguration.class);

    @Test
    void callStatusProjectionIsAbsentByDefault() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(FirebaseCallStatusProjectionService.class));
    }

    @Test
    void callStatusFlagCannotActivateProjectionWithoutRealtimeBridge() {
        contextRunner.withPropertyValues("nexachat.firebase.call-status-enabled=true")
                .run(context ->
                        assertThat(context).doesNotHaveBean(FirebaseCallStatusProjectionService.class));
    }

    @Test
    void realtimeBridgeAloneDoesNotActivateCallStatusProjection() {
        contextRunner.withPropertyValues(
                        "nexachat.firebase.realtime-enabled=true",
                        "nexachat.firebase.database-url=https://nexachat.firebaseio.com")
                .run(context ->
                        assertThat(context).doesNotHaveBean(FirebaseCallStatusProjectionService.class));
    }
}
