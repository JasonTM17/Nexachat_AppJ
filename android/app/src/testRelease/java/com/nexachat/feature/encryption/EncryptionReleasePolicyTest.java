package com.nexachat.feature.encryption;

import static org.junit.Assert.assertFalse;

import com.nexachat.BuildConfig;
import org.junit.Test;

/**
 * Release-policy guard: the compiled flag is false in the release variant even
 * when the build is invoked with {@code -Pe2eeV1ActivationEnabled=true}. The
 * property is only honored by debug and localSmoke.
 */
public final class EncryptionReleasePolicyTest {

    @Test
    public void releaseBuildNeverAllowsV1Activation() {
        assertFalse("release must never compile experimental E2EE v1 activation on",
                BuildConfig.E2EE_V1_ACTIVATION_ALLOWED);
    }
}
