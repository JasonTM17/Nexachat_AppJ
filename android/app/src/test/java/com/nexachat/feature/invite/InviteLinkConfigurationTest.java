package com.nexachat.feature.invite;

import com.nexachat.BuildConfig;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Static checks for the default invite-link configuration, manifest merge, and host boundaries. */
public final class InviteLinkConfigurationTest {
    private static final String PREVIOUS_UNOWNED_HOST = "nexachat" + ".app";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String OWNED_RELEASE_HOST = "nexachat.io.vn";

    @Test
    public void productionSourcesDoNotClaimThePreviousUnownedDomain() throws IOException {
        File appRoot = appRoot();

        for (String sourceDirName : List.of("src/main", "src/release")) {
            File sourceDir = new File(appRoot, sourceDirName);
            if (!sourceDir.isDirectory()) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceDir.toPath())) {
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
        String expectedUri = host.isEmpty()
                ? "nexachat://join/{token}"
                : "https://" + host + "/join/{token}";
        assertTrue("navigation pattern must match the BuildConfig host",
                expectedUri.equals(uriPattern.group(1).trim()));
    }

    @Test
    public void deepLinkUrisResolveNonEmptyForDebugLocalSmokeAndRelease() throws IOException {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("debug", "nexachat://join/{token}");
        expected.put("localSmoke", "nexachat://join/{token}");
        expected.put("release", "https://" + OWNED_RELEASE_HOST + "/join/{token}");

        for (Map.Entry<String, String> variant : expected.entrySet()) {
            String generated = generatedInvitePattern(variant.getKey());
            assertFalse(variant.getKey() + " deep link pattern must not be empty",
                    generated.isEmpty());
            assertEquals(variant.getKey() + " deep link pattern", variant.getValue(), generated);
            if (!"release".equals(variant.getKey())) {
                assertFalse(variant.getKey() + " must not register an https invite pattern",
                        generated.startsWith("https:"));
            }
            for (String nav : navigationDocuments(variant.getKey())) {
                for (String uri : deepLinkUris(nav)) {
                    String resolved = resolveDeepLinkUri(uri, generated);
                    assertFalse(variant.getKey() + " deepLink app:uri resolves empty: " + uri,
                            resolved.trim().isEmpty());
                }
            }
        }

        File resValuesRoot = new File(appRoot(), "build/generated/res/resValues");
        assertTrue(resValuesRoot.isDirectory());
        try (Stream<Path> files = Files.walk(resValuesRoot.toPath())) {
            files.filter(path -> path.getFileName().toString().equals("gradleResValues.xml"))
                    .forEach(path -> assertGeneratedInvitePatternNonEmpty(path));
        }
    }

    @Test
    public void mergedReleaseManifestContainsVerifiedHttpsInviteFilterAndDebugHasNone() throws Exception {
        File releaseMergedFile = new File(appRoot(),
                "build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml");
        assertTrue("release merged manifest must exist", releaseMergedFile.isFile());
        Document releaseDoc = parseXml(releaseMergedFile);

        Element releaseMainActivity = findMainActivity(releaseDoc);
        assertNotNull("MainActivity must be present in release manifest", releaseMainActivity);
        assertEquals("MainActivity must have exported=true", "true",
                releaseMainActivity.getAttributeNS(ANDROID_NS, "exported"));

        List<Element> releaseIntentFilters = childElementsByTag(releaseMainActivity, "intent-filter");
        assertEquals("MainActivity in release must have exactly 3 intent filters", 3, releaseIntentFilters.size());

        // Exactly one filter on MainActivity has autoVerify="true" and scheme="https"
        List<Element> httpsFilters = releaseIntentFilters.stream()
                .filter(filter -> "true".equals(filter.getAttributeNS(ANDROID_NS, "autoVerify")))
                .toList();
        assertEquals("release MainActivity must have exactly 1 autoVerify intent filter", 1, httpsFilters.size());
        Element httpsFilter = httpsFilters.get(0);

        List<Element> httpsData = childElementsByTag(httpsFilter, "data");
        assertEquals("https intent filter must have exactly 1 data element", 1, httpsData.size());
        Element data = httpsData.get(0);
        assertEquals("https", data.getAttributeNS(ANDROID_NS, "scheme"));
        assertEquals(OWNED_RELEASE_HOST, data.getAttributeNS(ANDROID_NS, "host"));
        assertEquals("/join/", data.getAttributeNS(ANDROID_NS, "pathPrefix"));

        // Verify across the entire release manifest, there is exactly one https intent filter
        NodeList allReleaseData = releaseDoc.getElementsByTagName("data");
        int totalHttpsDataCount = 0;
        for (int i = 0; i < allReleaseData.getLength(); i++) {
            Element d = (Element) allReleaseData.item(i);
            if ("https".equals(d.getAttributeNS(ANDROID_NS, "scheme"))) {
                totalHttpsDataCount++;
                assertEquals(OWNED_RELEASE_HOST, d.getAttributeNS(ANDROID_NS, "host"));
                assertEquals("/join/", d.getAttributeNS(ANDROID_NS, "pathPrefix"));
            }
        }
        assertEquals("release manifest must have exactly one https data element", 1, totalHttpsDataCount);

        // Verify release generated deep link resValue
        File releaseValues = new File(appRoot(),
                "build/generated/res/resValues/release/values/gradleResValues.xml");
        assertTrue("release gradleResValues.xml must exist", releaseValues.isFile());
        String releaseValuesXml = new String(Files.readAllBytes(releaseValues.toPath()),
                StandardCharsets.UTF_8);
        assertTrue(releaseValuesXml.contains(
                "<string name=\"invite_https_deep_link_pattern\" translatable=\"false\">https://"
                        + OWNED_RELEASE_HOST + "/join/{token}</string>"));

        // Now verify debug merged manifest has NO https filter
        File debugMergedFile = new File(appRoot(),
                "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml");
        assertTrue("debug merged manifest must exist", debugMergedFile.isFile());
        Document debugDoc = parseXml(debugMergedFile);

        Element debugMainActivity = findMainActivity(debugDoc);
        assertNotNull("MainActivity must be present in debug manifest", debugMainActivity);
        assertEquals("MainActivity must have exported=true", "true",
                debugMainActivity.getAttributeNS(ANDROID_NS, "exported"));

        List<Element> debugIntentFilters = childElementsByTag(debugMainActivity, "intent-filter");
        for (Element filter : debugIntentFilters) {
            assertFalse("debug intent filters must not have autoVerify",
                    "true".equals(filter.getAttributeNS(ANDROID_NS, "autoVerify")));
        }

        NodeList allDebugData = debugDoc.getElementsByTagName("data");
        for (int i = 0; i < allDebugData.getLength(); i++) {
            Element d = (Element) allDebugData.item(i);
            assertFalse("debug manifest must have no https scheme",
                    "https".equals(d.getAttributeNS(ANDROID_NS, "scheme")));
        }
    }

    private static void assertGeneratedInvitePatternNonEmpty(Path path) {
        try {
            String valuesXml = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Matcher uriPattern = Pattern.compile(
                    "<string\\s+name=\"invite_https_deep_link_pattern\"[^>]*>(.*?)</string>",
                    Pattern.DOTALL).matcher(valuesXml);
            if (!uriPattern.find()) {
                return;
            }
            assertFalse(path + " invite deep link pattern must not be empty",
                    uriPattern.group(1).trim().isEmpty());
        } catch (IOException error) {
            throw new IllegalStateException("Could not read " + path, error);
        }
    }

    private static String generatedInvitePattern(String variant) throws IOException {
        File generatedValues = new File(appRoot(),
                "build/generated/res/resValues/" + variant + "/values/gradleResValues.xml");
        assertTrue(variant + " gradleResValues.xml must exist", generatedValues.isFile());
        String valuesXml = new String(Files.readAllBytes(generatedValues.toPath()),
                StandardCharsets.UTF_8);
        Matcher uriPattern = Pattern.compile(
                "<string\\s+name=\"invite_https_deep_link_pattern\"[^>]*>(.*?)</string>",
                Pattern.DOTALL).matcher(valuesXml);
        assertTrue(variant + " must define invite_https_deep_link_pattern", uriPattern.find());
        return uriPattern.group(1).trim();
    }

    private static List<String> navigationDocuments(String variant) throws IOException {
        List<String> documents = new ArrayList<>();
        File mainDir = new File(appRoot(), "src/main/res/navigation");
        File variantDir = new File(appRoot(), "src/" + variant + "/res/navigation");
        List<String> overridden = new ArrayList<>();
        if (variantDir.isDirectory()) {
            File[] files = variantDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().endsWith(".xml")) {
                        overridden.add(file.getName());
                        documents.add(new String(Files.readAllBytes(file.toPath()),
                                StandardCharsets.UTF_8));
                    }
                }
            }
        }
        File[] mainFiles = mainDir.listFiles();
        if (mainFiles != null) {
            for (File file : mainFiles) {
                if (file.getName().endsWith(".xml") && !overridden.contains(file.getName())) {
                    documents.add(new String(Files.readAllBytes(file.toPath()),
                            StandardCharsets.UTF_8));
                }
            }
        }
        assertFalse(variant + " must have a navigation graph", documents.isEmpty());
        return documents;
    }

    private static List<String> deepLinkUris(String nav) {
        List<String> uris = new ArrayList<>();
        Matcher matcher = Pattern.compile("<deepLink\\b[^>]*\\bapp:uri=\"([^\"]*)\"")
                .matcher(nav);
        while (matcher.find()) {
            uris.add(matcher.group(1));
        }
        assertFalse("navigation graph must declare a deep link", uris.isEmpty());
        return uris;
    }

    private static String resolveDeepLinkUri(String raw, String generatedPattern) {
        if ("@string/invite_https_deep_link_pattern".equals(raw)) {
            return generatedPattern;
        }
        if (raw.startsWith("@string/")) {
            throw new AssertionError("Deep link resource is not a generated invite pattern: " + raw);
        }
        return raw;
    }

    private static Document parseXml(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(file);
    }

    private static Element findMainActivity(Document doc) {
        NodeList activities = doc.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            String name = activity.getAttributeNS(ANDROID_NS, "name");
            if ("com.nexachat.MainActivity".equals(name) || ".MainActivity".equals(name)) {
                return activity;
            }
        }
        return null;
    }

    private static List<Element> childElementsByTag(Element parent, String tagName) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && tagName.equals(el.getTagName())) {
                elements.add(el);
            }
        }
        return elements;
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
