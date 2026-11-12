package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * JVM unit test computing WCAG contrast for v2 semantic pairs and the legacy
 * sender, tick, and Material role checks.
 */
public final class ContrastUnitTest {

    private static final Pattern PAIR_ROW = Pattern.compile(
            "\\{\\s*\"theme\"\\s*:\\s*\"(light|dark)\"\\s*,\\s*"
                    + "\"use\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"\\s*,\\s*"
                    + "\"foreground\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*"
                    + "\"background\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*"
                    + "\"minimum\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*\\}",
            Pattern.DOTALL);

    private static final String[] SELECTORS = {
            "stitch_chip_bg_tint.xml",
            "stitch_chip_text_color.xml",
            "stitch_chip_stroke_color.xml",
            "nexa_filled_button_bg.xml",
            "nexa_filled_button_text.xml",
            "nexa_outlined_button_text.xml"
    };

    private File resDir;

    @Before
    public void setUp() {
        resDir = locateResDir();
    }

    private static File locateResDir() {
        File[] candidates = new File[] {
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
                new File("../app/src/main/res")
        };
        for (File candidate : candidates) {
            if (candidate.exists() && candidate.isDirectory()
                    && new File(candidate, "values/colors_nexachat.xml").exists()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate res directory from " + new File(".").getAbsolutePath());
    }

    public static double computeLuminance(String hex) {
        int argb = parseArgb(hex);
        double r = adjustComponent(((argb >> 16) & 0xFF) / 255.0);
        double g = adjustComponent(((argb >> 8) & 0xFF) / 255.0);
        double b = adjustComponent((argb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double adjustComponent(double c) {
        if (c <= 0.04045) {
            return c / 12.92;
        }
        return Math.pow((c + 0.055) / 1.055, 2.4);
    }

    public static double computeContrastRatio(String hex1, String hex2) {
        double l1 = computeLuminance(hex1);
        double l2 = computeLuminance(hex2);
        double bright = Math.max(l1, l2);
        double dark = Math.min(l1, l2);
        return (bright + 0.05) / (dark + 0.05);
    }

    public static int parseArgb(String hex) {
        String clean = hex.trim();
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        }
        if (clean.length() == 6) {
            clean = "FF" + clean;
        }
        if (clean.length() != 8) {
            throw new IllegalArgumentException("Expected #RRGGBB or #AARRGGBB, got " + hex);
        }
        return (int) Long.parseLong(clean, 16);
    }

    public static boolean isOpaque(String hex) {
        return ((parseArgb(hex) >>> 24) & 0xFF) == 0xFF;
    }

    /** Src-over composite. Used only when a fixture names a background; real text roles stay opaque. */
    public static String compositeOver(String foreground, String background) {
        int fg = parseArgb(foreground);
        int bg = parseArgb(background);
        double srcA = ((fg >>> 24) & 0xFF) / 255.0;
        double dstA = ((bg >>> 24) & 0xFF) / 255.0;
        double outA = srcA + dstA * (1.0 - srcA);
        if (outA <= 0.0) {
            throw new IllegalArgumentException("Cannot composite fully transparent colors");
        }
        int outR = channel(fg, bg, 16, srcA, dstA, outA);
        int outG = channel(fg, bg, 8, srcA, dstA, outA);
        int outB = channel(fg, bg, 0, srcA, dstA, outA);
        int outAlpha = (int) Math.round(outA * 255.0);
        return String.format("#%02X%02X%02X%02X", outAlpha, outR, outG, outB);
    }

    private static int channel(int fg, int bg, int shift, double srcA, double dstA, double outA) {
        double src = ((fg >> shift) & 0xFF) / 255.0;
        double dst = ((bg >> shift) & 0xFF) / 255.0;
        double out = (src * srcA + dst * dstA * (1.0 - srcA)) / outA;
        return Math.max(0, Math.min(255, (int) Math.round(out * 255.0)));
    }

    private Map<String, String> loadTheme(boolean night) throws Exception {
        Map<String, String> raw = new HashMap<>();
        loadColorDirectory(new File(resDir, "values"), raw);
        if (night) {
            loadColorDirectory(new File(resDir, "values-night"), raw);
        }
        Map<String, String> resolved = new HashMap<>();
        for (String name : raw.keySet()) {
            resolved.put(name, resolveColorValue(name, raw, new HashSet<>()));
        }
        return resolved;
    }

    private static void loadColorDirectory(File directory, Map<String, String> raw) throws Exception {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".xml"));
        if (files == null) {
            return;
        }
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        for (File file : files) {
            Document doc = builder.parse(file);
            NodeList colorNodes = doc.getElementsByTagName("color");
            for (int i = 0; i < colorNodes.getLength(); i++) {
                Element el = (Element) colorNodes.item(i);
                if (!el.hasAttribute("name")) {
                    continue;
                }
                raw.put(el.getAttribute("name"), el.getTextContent().trim());
            }
        }
    }

    static String resolveColorValue(String name, Map<String, String> rawColors, Set<String> stack) {
        if (!stack.add(name)) {
            throw new IllegalStateException("Color alias cycle at " + name);
        }
        String val = rawColors.get(name);
        if (val == null) {
            throw new IllegalArgumentException("Unknown color name: " + name);
        }
        if (val.startsWith("@color/")) {
            String resolved = resolveColorValue(val.substring("@color/".length()), rawColors, stack);
            stack.remove(name);
            return resolved;
        }
        stack.remove(name);
        return val;
    }

    @Test
    public void testLightModeContrastRatios() throws Exception {
        Map<String, String> colors = loadTheme(false);
        assertLegacyMaterialPairs("Light", colors);
        assertContrastAtLeast("Light: tick_read on other bubble",
                colors.get("tick_read"), colors.get("bubble_other"), 3.0);
        assertBubblePaletteContrast("Light", colors);
    }

    @Test
    public void testDarkModeContrastRatios() throws Exception {
        Map<String, String> colors = loadTheme(true);
        assertLegacyMaterialPairs("Dark", colors);
        assertContrastAtLeast("Dark: tick_read on other bubble",
                colors.get("tick_read"), colors.get("bubble_other"), 3.0);
        assertBubblePaletteContrast("Dark", colors);
    }

    @Test
    public void designV2PairsResolveFromSourceAndMeetThresholds() throws Exception {
        File fixture = locateFixture();
        String json = readUtf8(fixture);
        assertFalse("Fixture must name roles, not hex values", json.contains("#"));
        List<PairRow> rows = parseRows(json);
        assertEquals(270, rows.size());
        Map<String, String> light = loadTheme(false);
        Map<String, String> dark = loadTheme(true);
        int lightRows = 0;
        int darkRows = 0;
        for (PairRow row : rows) {
            Map<String, String> colors = "dark".equals(row.theme) ? dark : light;
            if ("dark".equals(row.theme)) {
                darkRows++;
            } else {
                lightRows++;
            }
            String fg = colors.get(row.foreground);
            String bg = colors.get(row.background);
            assertNotNull(row.theme + " missing " + row.foreground, fg);
            assertNotNull(row.theme + " missing " + row.background, bg);
            assertTrue(row.foreground + " must be opaque", isOpaque(fg));
            assertTrue(row.background + " must be opaque", isOpaque(bg));
            assertContrastAtLeast(row.theme + ": " + row.use + " " + row.foreground + "/" + row.background,
                    fg, bg, row.minimum);
        }
        assertEquals(135, lightRows);
        assertEquals(135, darkRows);
    }

    @Test
    public void failingForegroundOnContainerIsDetected() {
        try {
            assertContrastAtLeast("negative container pair", "#FF767676", "#FF777777", 4.5);
            fail("Close gray pair must fail the 4.5 threshold");
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage().contains("negative container pair"));
        }
    }

    @Test
    public void nonOpaqueRoleIsRejectedUnlessCompositedOverANamedBackground() {
        assertFalse(isOpaque("#80FFFCFF"));
        String composited = compositeOver("#80FFFCFF", "#FF000000");
        assertTrue(isOpaque(composited));
        assertTrue(computeContrastRatio(composited, "#FF000000") >= 1.0);
    }

    @Test
    public void aliasCyclesAreRejected() {
        Map<String, String> raw = new HashMap<>();
        raw.put("a", "@color/b");
        raw.put("b", "@color/a");
        try {
            resolveColorValue("a", raw, new HashSet<>());
            fail("Expected a cycle");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("cycle"));
        }
    }

    @Test
    public void darkModeFallsBackToBaseResources() throws Exception {
        Map<String, String> day = loadTheme(false);
        Map<String, String> night = loadTheme(true);
        assertEquals("#FF733F68", day.get("ic_launcher_background"));
        assertEquals("Launcher plate stays aubergine when night does not override it",
                day.get("ic_launcher_background"), night.get("ic_launcher_background"));
        assertFalse("Night must override brand_indigo",
                day.get("brand_indigo").equals(night.get("brand_indigo")));
        assertEquals("Avatar resources stay in the base file",
                day.get("avatar_indigo_container"), night.get("avatar_indigo_container"));
    }

    @Test
    public void selectorStatesKeepOpaqueTextAndFills() throws Exception {
        Map<String, String> light = loadTheme(false);
        Map<String, String> dark = loadTheme(true);
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        for (String name : SELECTORS) {
            File file = new File(resDir, "color/" + name);
            assertTrue(file.getPath(), file.isFile());
            Document doc = builder.parse(file);
            NodeList items = doc.getElementsByTagName("item");
            for (boolean enabled : new boolean[] {true, false}) {
                for (boolean checked : new boolean[] {true, false}) {
                    for (boolean pressed : new boolean[] {true, false}) {
                        Element match = firstMatch(items, enabled, checked, pressed);
                        assertNotNull(name + " missing state e=" + enabled + " c=" + checked + " p=" + pressed, match);
                        String reference = match.getAttribute("android:color");
                        for (Map<String, String> theme : new Map[] {light, dark}) {
                            String hex = reference.startsWith("@color/")
                                    ? theme.get(reference.substring("@color/".length()))
                                    : reference;
                            assertNotNull(name + " " + reference, hex);
                            assertTrue(name + " alpha must stay 1, was " + hex, isOpaque(hex));
                        }
                    }
                }
            }
        }
    }

    private static Element firstMatch(NodeList items, boolean enabled, boolean checked, boolean pressed) {
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if (stateMatches(item, "android:state_enabled", enabled)
                    && stateMatches(item, "android:state_checked", checked)
                    && stateMatches(item, "android:state_pressed", pressed)) {
                return item;
            }
        }
        return null;
    }

    private static boolean stateMatches(Element item, String attribute, boolean actual) {
        if (!item.hasAttribute(attribute)) {
            return true;
        }
        return Boolean.parseBoolean(item.getAttribute(attribute)) == actual;
    }

    private static void assertLegacyMaterialPairs(String theme, Map<String, String> colors) {
        assertContrastAtLeast(theme + ": onBackground on background",
                colors.get("colorOnBackground"), colors.get("colorBackground"), 4.5);
        assertContrastAtLeast(theme + ": onSurface on surface",
                colors.get("colorOnSurface"), colors.get("colorSurface"), 4.5);
        assertContrastAtLeast(theme + ": secondary text on surface",
                colors.get("colorOnSurfaceVariant"), colors.get("colorSurface"), 4.5);
        assertContrastAtLeast(theme + ": secondary text on surfaceVariant",
                colors.get("colorOnSurfaceVariant"), colors.get("colorSurfaceVariant"), 4.5);
        assertContrastAtLeast(theme + ": secondary text on background",
                colors.get("colorOnSurfaceVariant"), colors.get("colorBackground"), 4.5);
        assertContrastAtLeast(theme + ": onPrimary on primary",
                colors.get("colorOnPrimary"), colors.get("colorPrimary"), 4.5);
        assertContrastAtLeast(theme + ": onPrimaryContainer on primaryContainer",
                colors.get("colorOnPrimaryContainer"), colors.get("colorPrimaryContainer"), 4.5);
        assertContrastAtLeast(theme + ": onSecondary on secondary",
                colors.get("colorOnSecondary"), colors.get("colorSecondary"), 4.5);
        assertContrastAtLeast(theme + ": onSecondaryContainer on secondaryContainer",
                colors.get("colorOnSecondaryContainer"), colors.get("colorSecondaryContainer"), 4.5);
        assertContrastAtLeast(theme + ": onTertiary on tertiary",
                colors.get("colorOnTertiary"), colors.get("colorTertiary"), 4.5);
        assertContrastAtLeast(theme + ": onTertiaryContainer on tertiaryContainer",
                colors.get("colorOnTertiaryContainer"), colors.get("colorTertiaryContainer"), 4.5);
        assertContrastAtLeast(theme + ": onError on error",
                colors.get("colorOnError"), colors.get("colorError"), 4.5);
        assertContrastAtLeast(theme + ": onErrorContainer on errorContainer",
                colors.get("colorOnErrorContainer"), colors.get("colorErrorContainer"), 4.5);
        assertContrastAtLeast(theme + ": error on background",
                colors.get("colorError"), colors.get("colorBackground"), 4.5);
        assertContrastAtLeast(theme + ": pinned title on secondary container",
                colors.get("colorOnSecondaryContainer"), colors.get("colorSecondaryContainer"), 4.5);
    }

    private static void assertBubblePaletteContrast(String theme, Map<String, String> colors) {
        assertContrastAtLeast(theme + ": outgoing text on outgoing bubble",
                colors.get("chat_bubble_outgoing_text"), colors.get("bubble_mine"), 4.5);
        assertContrastAtLeast(theme + ": read tick on outgoing bubble",
                colors.get("chat_bubble_read_tick"), colors.get("bubble_mine"), 3.0);

        String[] senderColors = {
                "colorPrimary",
                "colorTertiary",
                "slate_muted",
                "colorError",
                "colorOnPrimaryContainer",
                "colorOnSecondaryContainer",
                "colorOnTertiaryContainer",
                "colorOnErrorContainer"
        };
        for (int index = 0; index < senderColors.length; index++) {
            assertContrastAtLeast(theme + ": sender palette " + index + " on incoming bubble",
                    colors.get(senderColors[index]), colors.get("bubble_other"), 4.5);
        }
    }

    private static void assertContrastAtLeast(String label, String fg, String bg, double minRatio) {
        assertNotNull(label + " foreground was null", fg);
        assertNotNull(label + " background was null", bg);
        assertTrue(label + " foreground is not opaque: " + fg, isOpaque(fg));
        assertTrue(label + " background is not opaque: " + bg, isOpaque(bg));
        double ratio = computeContrastRatio(fg, bg);
        assertTrue(String.format("%s: expected ratio >= %.1f:1 but was %.2f:1 (fg=%s, bg=%s)",
                label, minRatio, ratio, fg, bg), ratio >= minRatio - 0.0);
    }

    private static String readUtf8(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static File locateFixture() {
        File[] candidates = new File[] {
                new File("src/test/resources/design-v2-contrast-pairs.json"),
                new File("app/src/test/resources/design-v2-contrast-pairs.json"),
                new File("android/app/src/test/resources/design-v2-contrast-pairs.json")
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Missing design-v2-contrast-pairs.json");
    }

    private static List<PairRow> parseRows(String json) {
        List<PairRow> rows = new ArrayList<>();
        Matcher matcher = PAIR_ROW.matcher(json);
        while (matcher.find()) {
            PairRow row = new PairRow();
            row.theme = matcher.group(1);
            row.use = matcher.group(2);
            row.foreground = matcher.group(3);
            row.background = matcher.group(4);
            row.minimum = Double.parseDouble(matcher.group(5));
            rows.add(row);
        }
        return rows;
    }

    private static final class PairRow {
        String theme;
        String use;
        String foreground;
        String background;
        double minimum;
    }
}
