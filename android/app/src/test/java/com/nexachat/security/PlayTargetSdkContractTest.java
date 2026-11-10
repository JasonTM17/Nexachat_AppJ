package com.nexachat.security;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Play requires new apps and updates to target API 36. AGP 8.7 cannot
 * compile that SDK; 8.9 is the compatible line and 9.x stays deferred.
 */
public final class PlayTargetSdkContractTest {

    @Test
    public void releaseTargetsAndroid16WithAgp89() throws Exception {
        String gradle = read(locate(
                new File("build.gradle"),
                new File("app/build.gradle"),
                new File("android/app/build.gradle")));
        String catalog = read(locate(
                new File("../gradle/libs.versions.toml"),
                new File("gradle/libs.versions.toml"),
                new File("android/gradle/libs.versions.toml")));

        assertEqualsToken("compileSdk", 36, gradle);
        assertEqualsToken("targetSdk", 36, gradle);
        Matcher agp = Pattern.compile("(?m)^agp\\s*=\\s*\"(\\d+)\\.(\\d+)\\.(\\d+)\"")
                .matcher(catalog);
        assertTrue("version catalog must pin AGP", agp.find());
        int major = Integer.parseInt(agp.group(1));
        int minor = Integer.parseInt(agp.group(2));
        assertTrue("AGP must be 8.9.x so compileSdk 36 builds; 9.x is a separate upgrade",
                major == 8 && minor >= 9 && minor < 10);
    }

    private static void assertEqualsToken(String name, int expected, String gradle) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + name + "\\s+(\\d+)").matcher(gradle);
        assertTrue(name + " must be declared", matcher.find());
        assertTrue(name + " must be " + expected + " but was " + matcher.group(1),
                Integer.parseInt(matcher.group(1)) == expected);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File locate(File... candidates) {
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate Android Gradle files from "
                + new File(".").getAbsolutePath());
    }
}
