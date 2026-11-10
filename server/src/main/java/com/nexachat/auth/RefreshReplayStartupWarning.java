package com.nexachat.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Emits one startup warning when keyed replay recovery is unavailable. */
@Component
public class RefreshReplayStartupWarning implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RefreshReplayStartupWarning.class);

    private final RefreshReplayCipher replayCipher;

    public RefreshReplayStartupWarning(RefreshReplayCipher replayCipher) {
        this.replayCipher = replayCipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!replayCipher.isKeyAvailable()) {
            log.warn("Refresh replay key is unavailable; keyed refresh requests use legacy strict rotation without replay recovery");
        }
    }
}
