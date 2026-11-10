package com.nexachat.observability;

import com.nexachat.auth.RefreshReplayCipher;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Shows replay-key readiness without stopping the legacy refresh path. */
@Component("refreshReplay")
public class RefreshReplayHealthIndicator implements HealthIndicator {

    private final RefreshReplayCipher replayCipher;
    private final Environment environment;

    public RefreshReplayHealthIndicator(RefreshReplayCipher replayCipher, Environment environment) {
        this.replayCipher = replayCipher;
        this.environment = environment;
    }

    @Override
    public Health health() {
        if (replayCipher.isKeyAvailable()) {
            return Health.up().withDetail("mode", "encrypted-replay").build();
        }
        Health.Builder status = environment.acceptsProfiles(Profiles.of("prod"))
                ? Health.down()
                : Health.up();
        return status.withDetail("mode", "legacy-strict-rotation").build();
    }
}
