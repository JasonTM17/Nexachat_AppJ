package com.nexachat.e2ee;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Rollout knobs for experimental E2EE v1. {@code v1ActivationEnabled} is an
 * activation gate only: while false, a DIRECT conversation cannot newly turn
 * encryption on. It never rewrites existing state, never blocks turning
 * encryption off or reading state, and is not a kill switch for chats that are
 * already enabled.
 */
@Component
@ConfigurationProperties(prefix = "nexachat.e2ee")
public class E2eeProperties {

    /**
     * Whether new false-&gt;true activation is allowed. Defaults to false:
     * deployments that still want experimental v1 opt in explicitly.
     */
    private boolean v1ActivationEnabled = false;

    public boolean isV1ActivationEnabled() {
        return v1ActivationEnabled;
    }

    public void setV1ActivationEnabled(boolean v1ActivationEnabled) {
        this.v1ActivationEnabled = v1ActivationEnabled;
    }
}
