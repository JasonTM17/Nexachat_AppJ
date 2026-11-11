package com.nexachat.core.auth;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;

import org.junit.Test;

import kotlin.KotlinVersion;
import kotlin.Metadata;

/**
 * Guards the googleid pin in {@code gradle/libs.versions.toml}.
 *
 * <p>AGP 8.9.3's lint embeds the Kotlin 2.1.0 compiler and the resolved Kotlin
 * runtime is 2.1.20; both reject class metadata newer than their own compiler.
 * googleid 1.2.1 is built with Kotlin 2.4 metadata and produced the
 * "Module was compiled with an incompatible version of Kotlin" error on every
 * lint analysis. This test fails if the dependency is bumped past the metadata
 * version the toolchain can read, so the pin cannot silently regress.
 */
public final class GoogleIdKotlinMetadataTest {

    @Test
    public void googleIdMetadataIsReadableByTheResolvedKotlinRuntime() {
        Metadata metadata = GetGoogleIdOption.class.getAnnotation(Metadata.class);
        assertNotNull("googleid classes must carry Kotlin metadata", metadata);

        int[] version = metadata.mv();
        String actual = version[0] + "." + version[1] + "." + version[2];
        assertTrue(
                "googleid metadata " + actual + " is newer than the resolved Kotlin "
                        + KotlinVersion.CURRENT,
                version[0] < KotlinVersion.CURRENT.getMajor()
                        || (version[0] == KotlinVersion.CURRENT.getMajor()
                                && version[1] <= KotlinVersion.CURRENT.getMinor()));
    }
}
