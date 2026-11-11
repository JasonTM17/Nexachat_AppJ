package com.nexachat.firebase.projection;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FirebaseProjectionConfigurationTest {

    @Test
    void projectionBeansAreAbsentWhenRealtimeFlagsUseTheirDefaults() {
        new ApplicationContextRunner()
                .withUserConfiguration(FirebaseProjectionConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FirebaseAuthorizationProjectionService.class);
                    assertThat(context).doesNotHaveBean(FirebaseAuthorizationBackfillJob.class);
                    assertThat(context).doesNotHaveBean(FirebaseAuthorizationLeaseSweepJob.class);
                });
    }
}
