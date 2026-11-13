package com.nexachat.res;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Archive badge contrast, fallback tint wiring, and account/archive control styles. */
public final class RemainingAccountArchiveUiContractTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String NS_APP = "http://schemas.android.com/apk/res-auto";
    private static final double BADGE_MIN_CONTRAST = 4.5;

    private File resDir;

    @Before
    public void setUp() {
        resDir = locateResDir();
    }

    @Test
    public void archiveVideoBadgeUsesMediaMetaInk() throws Exception {
        Element badge = requireId("layout/item_archive_media.xml", "archiveMediaKind");
        assertTrue(badge.getAttributeNS(NS_ANDROID, "textColor").contains("nexa_on_media_meta"));
    }

    @Test
    public void archiveVideoBadgeContrastMeetsWcagInLightAndDark() throws Exception {
        Map<String, String> light = loadPalette(new File(resDir, "values/colors_nexachat.xml"));
        Map<String, String> dark = loadPalette(new File(resDir, "values-night/colors_nexachat.xml"));
        for (Map.Entry<String, Map<String, String>> themed :
                new Map.Entry[] {
                    Map.entry("light", light),
                    Map.entry("dark", dark),
                }) {
            double ratio = ContrastUnitTest.computeContrastRatio(
                    themed.getValue().get("nexa_on_media_meta"),
                    themed.getValue().get("nexa_media_meta_surface"));
            assertTrue(
                    themed.getKey() + " badge contrast=" + ratio,
                    ratio >= BADGE_MIN_CONTRAST);
        }
    }

    @Test
    public void archiveFilterButtonsUseNexaChatOutlinedAndTextStyles() throws Exception {
        assertButtonStyle("layout/fragment_archive.xml", "archiveSenderFilter", "Widget.NexaChat.Button.Outlined");
        assertButtonStyle("layout/fragment_archive.xml", "archiveDateFilter", "Widget.NexaChat.Button.Outlined");
        assertButtonStyle("layout/fragment_archive.xml", "archiveClearFilters", "Widget.NexaChat.Button.Text");
    }

    @Test
    public void deleteAccountPrimaryButtonsUseNexaChatStyleWithDestructiveOverrides() throws Exception {
        Element sendCode = requireId("layout/fragment_delete_account.xml", "sendDeletionCode");
        assertTrue(sendCode.getAttribute("style").contains("Widget.NexaChat.Button"));
        assertFalse(sendCode.getAttribute("style").contains("Widget.Material3"));

        Element confirm = requireId("layout/fragment_delete_account.xml", "confirmDelete");
        assertTrue(confirm.getAttribute("style").contains("Widget.NexaChat.Button"));
        assertTrue(confirm.getAttributeNS(NS_ANDROID, "textColor").contains("colorOnError"));
        assertTrue(confirm.getAttributeNS(NS_APP, "backgroundTint").contains("colorError"));
        assertTrue(confirm.getAttributeNS(NS_ANDROID, "minHeight").contains("56"));
    }

    @Test
    public void languageSettingsRadioUsesNexaChatBodyLarge() throws Exception {
        String source = readSource("feature/profile/LanguageSettingsFragment.java");
        assertTrue(source.contains("R.style.TextAppearance_NexaChat_BodyLarge"));
        assertFalse(source.contains("TextAppearance_Material3_BodyLarge"));
    }

    @Test
    public void archiveMediaAdapterTintsFallbackPlaceholder() throws Exception {
        String source = readSource("feature/archive/ArchiveMediaAdapter.java");
        assertTrue(source.contains("colorOnSurfaceVariant"));
        assertTrue(source.contains("ic_image"));
        assertFalse(source.contains("setImageResource(R.drawable.ic_image)"));
    }

    private void assertButtonStyle(String layoutPath, String id, String styleToken) throws Exception {
        Element button = requireId(layoutPath, id);
        assertTrue(button.getAttribute("style").contains(styleToken));
        assertFalse(button.getAttribute("style").contains("Widget.Material3"));
    }

    private Element requireId(String layoutPath, String id) throws Exception {
        Document doc = parse(new File(resDir, layoutPath));
        NodeList nodes = doc.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(NS_ANDROID, "id"))) {
                return node;
            }
        }
        throw new AssertionError("Missing @" + id + " in " + layoutPath);
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }

    private static Map<String, String> loadPalette(File file) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        NodeList nodes = doc.getElementsByTagName("color");
        Map<String, String> raw = new HashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            raw.put(el.getAttribute("name"), el.getTextContent().trim());
        }
        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            resolved.put(entry.getKey(), resolveColor(raw, entry.getValue()));
        }
        return resolved;
    }

    private static String resolveColor(Map<String, String> raw, String value) {
        if (value.startsWith("@color/")) {
            String ref = value.substring("@color/".length());
            return resolveColor(raw, raw.getOrDefault(ref, value));
        }
        return value;
    }

    private static String readSource(String relative) throws Exception {
        Path[] candidates = new Path[] {
                Path.of("src/main/java/com/nexachat", relative),
                Path.of("app/src/main/java/com/nexachat", relative),
                Path.of("android/app/src/main/java/com/nexachat", relative),
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing " + relative);
    }

    private static File locateResDir() {
        File[] candidates = new File[] {
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory() && new File(candidate, "values/colors_nexachat.xml").isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("res dir not found");
    }
}
