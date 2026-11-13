package com.nexachat.feature.chat.adapter;

import com.nexachat.R;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class MessageBubbleSenderPaletteTest {
    private static final int[] EXPECTED_COLOR_RESOURCES = {
            R.color.colorPrimary,
            R.color.colorTertiary,
            R.color.slate_muted,
            R.color.colorSecondary,
            R.color.colorOnPrimaryContainer,
            R.color.colorOnSecondaryContainer,
            R.color.colorOnTertiaryContainer,
            R.color.slate_on_secondary_container
    };

    private static final String[] ROLE_NAMES = {
            "colorPrimary",
            "colorTertiary",
            "slate_muted",
            "colorSecondary",
            "colorOnPrimaryContainer",
            "colorOnSecondaryContainer",
            "colorOnTertiaryContainer",
            "slate_on_secondary_container"
    };

    @Test
    public void senderPaletteUsesExistingNonErrorRoles() {
        for (int i = 0; i < EXPECTED_COLOR_RESOURCES.length; i++) {
            assertEquals("palette slot " + i,
                    EXPECTED_COLOR_RESOURCES[i], MessageBubbleBinder.senderNameColorResourceAt(i));
        }
    }

    @Test
    public void everySenderRoleMeetsTextContrastOnIncomingBubbleInBothThemes() throws Exception {
        for (String theme : new String[]{"values", "values-night"}) {
            Map<String, String> colors = readColors(new File("src/main/res/" + theme + "/colors_nexachat.xml"));
            int incomingBubble = parseColor(resolveColor("bubble_other", colors));
            for (String role : ROLE_NAMES) {
                int foreground = parseColor(resolveColor(role, colors));
                double ratio = MessageBubbleBinder.contrastRatio(foreground, incomingBubble);
                assertTrue(theme + " " + role + " contrast=" + ratio, ratio >= 4.5d);
            }
        }
    }

    @Test
    public void contrastRatioUsesSrgbRelativeLuminance() {
        assertEquals(21d, MessageBubbleBinder.contrastRatio(0xFFFFFFFF, 0xFF000000), 0.01d);
        assertEquals(1d, MessageBubbleBinder.contrastRatio(0xFF4A2943, 0xFF4A2943), 0.01d);
    }

    private static Map<String, String> readColors(File file) throws Exception {
        Map<String, String> colors = new HashMap<>();
        NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file).getElementsByTagName("color");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element color = (Element) nodes.item(i);
            colors.put(color.getAttribute("name"), color.getTextContent().trim());
        }
        return colors;
    }

    private static String resolveColor(String name, Map<String, String> colors) {
        String value = colors.get(name);
        if (value == null) throw new AssertionError("Missing color " + name);
        if (value.startsWith("@color/")) return resolveColor(value.substring("@color/".length()), colors);
        return value;
    }

    private static int parseColor(String value) {
        String digits = value.substring(1);
        long parsed = Long.parseLong(digits, 16);
        return digits.length() == 6 ? (int) (0xFF000000L | parsed) : (int) parsed;
    }
}
