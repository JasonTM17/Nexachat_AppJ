package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit test computing WCAG 2.1 contrast ratios for Material 3 theme color pairs
 * in both light (values/colors_nexachat.xml) and dark (values-night/colors_nexachat.xml).
 */
public final class ContrastUnitTest {

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
            if (candidate.exists() && candidate.isDirectory() && new File(candidate, "values/colors_nexachat.xml").exists()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate res directory from " + new File(".").getAbsolutePath());
    }

    public static double computeLuminance(String hex) {
        String clean = hex.trim().replace("#", "");
        if (clean.length() == 8) {
            clean = clean.substring(2); // strip alpha
        }
        int rInt = Integer.parseInt(clean.substring(0, 2), 16);
        int gInt = Integer.parseInt(clean.substring(2, 4), 16);
        int bInt = Integer.parseInt(clean.substring(4, 6), 16);

        double r = adjustComponent(rInt / 255.0);
        double g = adjustComponent(gInt / 255.0);
        double b = adjustComponent(bInt / 255.0);

        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double adjustComponent(double c) {
        if (c <= 0.04045) {
            return c / 12.92;
        } else {
            return Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    public static double computeContrastRatio(String hex1, String hex2) {
        double l1 = computeLuminance(hex1);
        double l2 = computeLuminance(hex2);
        double bright = Math.max(l1, l2);
        double dark = Math.min(l1, l2);
        return (bright + 0.05) / (dark + 0.05);
    }

    private Map<String, String> loadAndResolveColors(File file) throws Exception {
        Map<String, String> rawColors = new HashMap<>();

        File[] files = new File[] {
                file,
                new File(file.getParentFile(), "chat_bubble_colors.xml")
        };
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        for (File colorFile : files) {
            if (!colorFile.isFile()) {
                continue;
            }
            Document doc = builder.parse(colorFile);
            Element root = doc.getDocumentElement();
            NodeList colorNodes = root.getElementsByTagName("color");
            for (int i = 0; i < colorNodes.getLength(); i++) {
                Element el = (Element) colorNodes.item(i);
                String name = el.getAttribute("name");
                String value = el.getTextContent().trim();
                rawColors.put(name, value);
            }
        }

        Map<String, String> resolved = new HashMap<>();
        for (String name : rawColors.keySet()) {
            resolved.put(name, resolveColorValue(name, rawColors));
        }
        return resolved;
    }

    private String resolveColorValue(String name, Map<String, String> rawColors) {
        String val = rawColors.get(name);
        if (val == null) {
            if (name.equals("white") || name.equalsIgnoreCase("#FFFFFFFF")) {
                return "#FFFFFFFF";
            }
            throw new IllegalArgumentException("Unknown color name: " + name);
        }
        while (val.startsWith("@color/")) {
            String ref = val.substring("@color/".length());
            val = rawColors.get(ref);
            if (val == null) {
                throw new IllegalArgumentException("Unresolvable color reference: " + ref);
            }
        }
        return val;
    }

    @Test
    public void testLightModeContrastRatios() throws Exception {
        File lightFile = new File(resDir, "values/colors_nexachat.xml");
        Map<String, String> colors = loadAndResolveColors(lightFile);

        // Body text pairs (≥ 4.5:1)
        assertContrastAtLeast("Light: onBackground on background",
                colors.get("colorOnBackground"), colors.get("colorBackground"), 4.5);
        assertContrastAtLeast("Light: onSurface on surface",
                colors.get("colorOnSurface"), colors.get("colorSurface"), 4.5);
        assertContrastAtLeast("Light: secondary text on surface",
                colors.get("colorOnSurfaceVariant"), colors.get("colorSurface"), 4.5);
        assertContrastAtLeast("Light: secondary text on surfaceVariant",
                colors.get("colorOnSurfaceVariant"), colors.get("colorSurfaceVariant"), 4.5);
        assertContrastAtLeast("Light: secondary text on background",
                colors.get("colorOnSurfaceVariant"), colors.get("colorBackground"), 4.5);
        assertContrastAtLeast("Light: onPrimary on primary",
                colors.get("colorOnPrimary"), colors.get("colorPrimary"), 4.5);
        assertContrastAtLeast("Light: onPrimaryContainer on primaryContainer",
                colors.get("colorOnPrimaryContainer"), colors.get("colorPrimaryContainer"), 4.5);
        assertContrastAtLeast("Light: onSecondary on secondary",
                colors.get("colorOnSecondary"), colors.get("colorSecondary"), 4.5);
        assertContrastAtLeast("Light: onSecondaryContainer on secondaryContainer",
                colors.get("colorOnSecondaryContainer"), colors.get("colorSecondaryContainer"), 4.5);
        assertContrastAtLeast("Light: onTertiary on tertiary",
                colors.get("colorOnTertiary"), colors.get("colorTertiary"), 4.5);
        assertContrastAtLeast("Light: onTertiaryContainer on tertiaryContainer",
                colors.get("colorOnTertiaryContainer"), colors.get("colorTertiaryContainer"), 4.5);
        assertContrastAtLeast("Light: onError on error",
                colors.get("colorOnError"), colors.get("colorError"), 4.5);
        assertContrastAtLeast("Light: onErrorContainer on errorContainer",
                colors.get("colorOnErrorContainer"), colors.get("colorErrorContainer"), 4.5);
        assertContrastAtLeast("Light: error on background",
                colors.get("colorError"), colors.get("colorBackground"), 4.5);

        // Pinned title on secondary container
        assertContrastAtLeast("Light: pinned title on secondary container",
                colors.get("colorOnSecondaryContainer"), colors.get("colorSecondaryContainer"), 4.5);

        // Graphical elements / large icons (≥ 3.0:1)
        assertContrastAtLeast("Light: tick_read on other bubble",
                colors.get("tick_read"), colors.get("bubble_other"), 3.0);
        assertBubblePaletteContrast("Light", colors);
    }

    @Test
    public void testDarkModeContrastRatios() throws Exception {
        File darkFile = new File(resDir, "values-night/colors_nexachat.xml");
        Map<String, String> colors = loadAndResolveColors(darkFile);

        // Body text pairs (≥ 4.5:1)
        assertContrastAtLeast("Dark: onBackground on background",
                colors.get("colorOnBackground"), colors.get("colorBackground"), 4.5);
        assertContrastAtLeast("Dark: onSurface on surface",
                colors.get("colorOnSurface"), colors.get("colorSurface"), 4.5);
        assertContrastAtLeast("Dark: secondary text on surface",
                colors.get("colorOnSurfaceVariant"), colors.get("colorSurface"), 4.5);
        assertContrastAtLeast("Dark: secondary text on surfaceVariant",
                colors.get("colorOnSurfaceVariant"), colors.get("colorSurfaceVariant"), 4.5);
        assertContrastAtLeast("Dark: secondary text on background",
                colors.get("colorOnSurfaceVariant"), colors.get("colorBackground"), 4.5);
        assertContrastAtLeast("Dark: onPrimary on primary",
                colors.get("colorOnPrimary"), colors.get("colorPrimary"), 4.5);
        assertContrastAtLeast("Dark: onPrimaryContainer on primaryContainer",
                colors.get("colorOnPrimaryContainer"), colors.get("colorPrimaryContainer"), 4.5);
        assertContrastAtLeast("Dark: onSecondary on secondary",
                colors.get("colorOnSecondary"), colors.get("colorSecondary"), 4.5);
        assertContrastAtLeast("Dark: onSecondaryContainer on secondaryContainer",
                colors.get("colorOnSecondaryContainer"), colors.get("colorSecondaryContainer"), 4.5);
        assertContrastAtLeast("Dark: onTertiary on tertiary",
                colors.get("colorOnTertiary"), colors.get("colorTertiary"), 4.5);
        assertContrastAtLeast("Dark: onTertiaryContainer on tertiaryContainer",
                colors.get("colorOnTertiaryContainer"), colors.get("colorTertiaryContainer"), 4.5);
        assertContrastAtLeast("Dark: onError on error",
                colors.get("colorOnError"), colors.get("colorError"), 4.5);
        assertContrastAtLeast("Dark: onErrorContainer on errorContainer",
                colors.get("colorOnErrorContainer"), colors.get("colorErrorContainer"), 4.5);
        assertContrastAtLeast("Dark: error on background",
                colors.get("colorError"), colors.get("colorBackground"), 4.5);

        // Pinned title on secondary container
        assertContrastAtLeast("Dark: pinned title on secondary container",
                colors.get("colorOnSecondaryContainer"), colors.get("colorSecondaryContainer"), 4.5);

        // Graphical elements / large icons (≥ 3.0:1)
        assertContrastAtLeast("Dark: tick_read on other bubble",
                colors.get("tick_read"), colors.get("bubble_other"), 3.0);
        assertBubblePaletteContrast("Dark", colors);
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
        double ratio = computeContrastRatio(fg, bg);
        assertTrue(String.format("%s: expected ratio >= %.1f:1 but was %.2f:1 (fg=%s, bg=%s)",
                label, minRatio, ratio, fg, bg), ratio >= minRatio);
    }
}
