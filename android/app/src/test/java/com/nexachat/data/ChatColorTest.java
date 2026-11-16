package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Palette contract for the user-selectable outgoing bubble colour: stable ids,
 * the plum fallback, per-scheme variants, and the WCAG floor that keeps the
 * outgoing text readable on every swatch in both schemes.
 */
public final class ChatColorTest {

    private static final double MIN_TEXT_CONTRAST = 4.5;

    @Test
    public void idsAreStableAndUnique() {
        assertEquals(8, ChatColor.values().length);
        Set<String> ids = Arrays.stream(ChatColor.values())
                .map(ChatColor::name)
                .collect(Collectors.toSet());
        assertEquals(Set.of("PLUM", "INDIGO", "BLUE", "TEAL", "GREEN", "AMBER", "ROSE", "SLATE"), ids);
    }

    @Test
    public void fromNameRestoresStoredIdAndFallsBackToPlum() {
        for (ChatColor color : ChatColor.values()) {
            assertEquals(color, ChatColor.fromName(color.name()));
        }
        assertEquals(ChatColor.PLUM, ChatColor.fromName(null));
        assertEquals(ChatColor.PLUM, ChatColor.fromName("MAGENTA"));
        assertEquals(ChatColor.PLUM, ChatColor.fromName(""));
    }

    @Test
    public void everySwatchHasOpaqueDayAndNightVariants() {
        for (ChatColor color : ChatColor.values()) {
            assertEquals(color.name() + " day alpha", 0xFF, (color.getDayArgb() >>> 24) & 0xFF);
            assertEquals(color.name() + " night alpha", 0xFF, (color.getNightArgb() >>> 24) & 0xFF);
        }
    }

    @Test
    public void outgoingTextContrastHoldsForEverySwatchInBothSchemes() throws Exception {
        String dayText = colorHex("chat_bubble_outgoing_text", false);
        String nightText = colorHex("chat_bubble_outgoing_text", true);
        for (ChatColor color : ChatColor.values()) {
            assertContrastAtLeast(color.name() + " day", dayText, color.getDayArgb());
            assertContrastAtLeast(color.name() + " night", nightText, color.getNightArgb());
        }
    }

    @Test
    public void readTickContrastHoldsForEverySwatchInBothSchemes() throws Exception {
        String dayTick = colorHex("chat_bubble_read_tick", false);
        String nightTick = colorHex("chat_bubble_read_tick", true);
        for (ChatColor color : ChatColor.values()) {
            assertContrastAtLeast(color.name() + " day tick", dayTick, color.getDayArgb(), 3.0);
            assertContrastAtLeast(color.name() + " night tick", nightTick, color.getNightArgb(), 3.0);
        }
    }

    @Test
    public void plumMatchesTheLegacyBubbleMineTokens() throws Exception {
        // The stored default must render identically to the fixed plum resource
        // it replaces, so un-upgraded state and the new default agree.
        assertEquals(ChatColor.PLUM.getDayArgb(),
                parseArgb(colorHex("bubble_mine", false)));
        assertEquals(ChatColor.PLUM.getNightArgb(),
                parseArgb(colorHex("bubble_mine", true)));
    }

    private static void assertContrastAtLeast(String label, String foregroundHex, int backgroundArgb) {
        assertContrastAtLeast(label, foregroundHex, backgroundArgb, MIN_TEXT_CONTRAST);
    }

    private static void assertContrastAtLeast(
            String label, String foregroundHex, int backgroundArgb, double minimum) {
        double ratio = (Math.max(relativeLuminance(parseArgb(foregroundHex)), relativeLuminance(backgroundArgb)) + 0.05)
                / (Math.min(relativeLuminance(parseArgb(foregroundHex)), relativeLuminance(backgroundArgb)) + 0.05);
        assertTrue(String.format("%s: expected ratio >= %.1f:1 but was %.2f:1", label, minimum, ratio),
                ratio >= minimum);
    }

    private static double relativeLuminance(int argb) {
        double red = linearChannel((argb >> 16) & 0xFF);
        double green = linearChannel((argb >> 8) & 0xFF);
        double blue = linearChannel(argb & 0xFF);
        return 0.2126d * red + 0.7152d * green + 0.0722d * blue;
    }

    private static double linearChannel(int channel) {
        double srgb = channel / 255.0d;
        return srgb <= 0.04045d
                ? srgb / 12.92d
                : Math.pow((srgb + 0.055d) / 1.055d, 2.4d);
    }

    private static int parseArgb(String hex) {
        String clean = hex.trim();
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        }
        if (clean.length() == 6) {
            clean = "FF" + clean;
        }
        return (int) Long.parseLong(clean, 16);
    }

    /** Resolves a color name through the day or night resource directories. */
    private static String colorHex(String name, boolean night) throws Exception {
        Map<String, String> raw = new HashMap<>();
        File resDir = locateResDir();
        loadColors(new File(resDir, "values"), raw);
        if (night) {
            loadColors(new File(resDir, "values-night"), raw);
        }
        String value = resolve(name, raw, new HashSet<>());
        assertNotNull("Missing color " + name, value);
        return value;
    }

    private static void loadColors(File directory, Map<String, String> raw) throws Exception {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".xml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(file);
            NodeList nodes = doc.getElementsByTagName("color");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                if (el.hasAttribute("name")) {
                    raw.put(el.getAttribute("name"), el.getTextContent().trim());
                }
            }
        }
    }

    private static String resolve(String name, Map<String, String> raw, Set<String> stack) {
        if (!stack.add(name)) {
            throw new IllegalStateException("Color alias cycle at " + name);
        }
        String value = raw.get(name);
        if (value == null) {
            return null;
        }
        stack.remove(name);
        return value.startsWith("@color/")
                ? resolve(value.substring("@color/".length()), raw, stack)
                : value;
    }

    private static File locateResDir() {
        File[] candidates = new File[] {
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
                new File("../app/src/main/res")
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory()
                    && new File(candidate, "values/colors_nexachat.xml").exists()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate res directory");
    }
}
