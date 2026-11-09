package com.nexachat.feature.invite;

import com.nexachat.BuildConfig;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Static checks for the default invite-link configuration and host boundary. */
public final class InviteLinkConfigurationTest {
    private static final String PREVIOUS_UNOWNED_HOST = "nexachat" + ".app";

    @Test
    public void productionSourcesDoNotClaimThePreviousUnownedDomain() throws IOException {
        File appRoot = appRoot();

        try (Stream<Path> files = Files.walk(new File(appRoot, "src/main").toPath())) {
            files.filter(Files::isRegularFile)
                    .filter(InviteLinkConfigurationTest::isTextSource)
                    .forEach(path -> {
                try {
                    String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    assertFalse(path + " must not hard-code an unowned invite host",
                            source.contains(PREVIOUS_UNOWNED_HOST));
                } catch (IOException error) {
                    throw new IllegalStateException("Could not read invite configuration source", error);
                }
            });
        }

        String nav = new String(Files.readAllBytes(
                new File(appRoot, "src/main/res/navigation/nav_main.xml").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(nav.contains("nexachat://join/{token}"));
        assertTrue(nav.contains("@string/invite_https_deep_link_pattern"));
        assertFalse("HTTPS deep links are supplied only by a configured Gradle value",
                nav.contains("https://"));

        String gradle = new String(Files.readAllBytes(
                new File(appRoot, "build.gradle").toPath()), StandardCharsets.UTF_8);
        assertFalse(gradle.contains(PREVIOUS_UNOWNED_HOST));
        assertTrue(gradle.contains("providers.gradleProperty('inviteLinkHttpsHost').orElse('')"));
        assertTrue(gradle.contains("INVITE_LINK_HTTPS_HOST"));
    }

    @Test
    public void buildConfigHostControlsDefaultParsingAndShareOutput() {
        String host = BuildConfig.INVITE_LINK_HTTPS_HOST;
        String token = "A".repeat(43);

        if (host.isEmpty()) {
            assertTrue(InviteLinkParser.parse("nexachat://join/" + token).isPresent());
            assertTrue(InviteLinkParser.shareUrl(token).orElseThrow()
                    .startsWith("nexachat://join/"));
            assertFalse(InviteLinkParser.parse(
                    "https://invite.example.test/join/" + token).isPresent());
        } else {
            assertTrue(InviteLinkParser.parse(
                    "https://" + host + "/join/" + token).isPresent());
            assertTrue(InviteLinkParser.shareUrl(token).orElseThrow()
                    .startsWith("https://" + host + "/join/"));
            assertFalse(InviteLinkParser.parse(
                    "https://" + host + ".evil.test/join/" + token).isPresent());
        }
    }

    @Test
    public void mergedManifestAddsOnlyTheConfiguredHttpsInviteFilter() throws IOException {
        File mergedManifest = new File(appRoot(),
                "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml");
        assertTrue("debug merged manifest must exist before unit tests", mergedManifest.isFile());
        String manifest = new String(Files.readAllBytes(mergedManifest.toPath()),
                StandardCharsets.UTF_8);
        String host = BuildConfig.INVITE_LINK_HTTPS_HOST;

        if (host.isEmpty()) {
            assertFalse(manifest.contains("android:scheme=\"https\""));
        } else {
            assertTrue(manifest.contains("android:autoVerify=\"true\""));
            assertTrue(manifest.contains("android:scheme=\"https\""));
            assertTrue(manifest.contains("android:host=\"" + host + "\""));
            assertTrue(manifest.contains("android:pathPrefix=\"/join/\""));
        }

        File generatedValues = new File(appRoot(),
                "build/generated/res/resValues/debug/values/gradleResValues.xml");
        assertTrue("generated invite navigation pattern must exist", generatedValues.isFile());
        String valuesXml = new String(Files.readAllBytes(generatedValues.toPath()),
                StandardCharsets.UTF_8);
        Matcher uriPattern = Pattern.compile(
                "<string\\s+name=\"invite_https_deep_link_pattern\"[^>]*>(.*?)</string>")
                .matcher(valuesXml);
        assertTrue("Gradle must define the optional navigation pattern", uriPattern.find());
        String expectedUri = host.isEmpty() ? "" : "https://" + host + "/join/{token}";
        assertTrue("optional navigation pattern must match the BuildConfig host",
                expectedUri.equals(uriPattern.group(1).trim()));
    }

    private static File appRoot() {
        File[] candidates = {
                new File("."),
                new File("app"),
                new File("android/app"),
                new File("../app")
        };
        for (File candidate : candidates) {
            if (new File(candidate, "src/main/AndroidManifest.xml").isFile()
                    && new File(candidate, "build.gradle").isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate android/app from "
                + new File(".").getAbsolutePath());
    }

    private static boolean isTextSource(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".xml")
                || name.endsWith(".json") || name.endsWith(".properties");
    }
}
