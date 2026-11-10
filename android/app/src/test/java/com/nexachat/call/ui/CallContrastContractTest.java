package com.nexachat.call.ui;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.assertTrue;

/** Contrast against the actual fixed dark call surface in both app themes. */
public final class CallContrastContractTest {
    @Test public void materialMuteAndSpeakerTintIsExplicitAndContrasting() throws Exception {
        for (String dir : new String[]{"layout", "layout-land"}) {
            Path layout = Path.of("src/main/res", dir, "activity_call.xml");
            if (!Files.exists(layout)) continue;
            NodeList buttons = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(layout.toFile()).getElementsByTagName("com.google.android.material.button.MaterialButton");
            int checked = 0;
            for (int i = 0; i < buttons.getLength(); i++) {
                Element button = (Element) buttons.item(i);
                String id = button.getAttribute("android:id");
                if (!id.equals("@+id/call_mute") && !id.equals("@+id/call_speaker")) continue;
                String tint = button.getAttribute("android:backgroundTint");
                assertTrue(dir + " " + id + " must override Material's theme tint", tint.startsWith("@color/"));
                for (boolean night : new boolean[]{false, true}) {
                    Map<String, String> colors = new HashMap<>();
                    load(colors, "values");
                    if (night) load(colors, "values-night");
                    check(colors, "call_icon_on_control", tint.substring(7), 4.5);
                }
                checked++;
            }
            assertTrue(dir + " must retain both call controls", checked == 2);
        }
    }

    @Test public void callLabelsRemainLegibleAcrossTheEntireGradient() throws Exception {
        Element gradient = (Element) DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("src/main/res/drawable/bg_call_gradient.xml").toFile())
                .getElementsByTagName("gradient").item(0);
        for (boolean night : new boolean[]{false, true}) {
            Map<String, String> colors = new HashMap<>();
            load(colors, "values");
            if (night) load(colors, "values-night");
            for (String endpoint : new String[]{"startColor", "endColor"}) {
                colors.put("gradient", gradient.getAttribute("android:" + endpoint));
                for (String foreground : new String[]{"call_text_primary", "call_text_secondary",
                        "call_text_muted", "call_action_accept_text", "call_action_decline_text"}) {
                    check(colors, foreground, "gradient", 4.5);
                }
            }
        }
    }

    @Test public void avatarInitialsAndControlsRemainLegible() throws Exception {
        for (boolean night : new boolean[]{false, true}) {
            Map<String, String> colors = new HashMap<>();
            load(colors, "values");
            if (night) load(colors, "values-night");
            for (int i = 0; i < 8; i++) {
                check(colors, "call_avatar_fg_" + i, "call_avatar_bg_" + i, 4.5);
            }
            check(colors, "call_icon_on_control", "call_accept_background", 4.5);
            check(colors, "call_icon_on_control", "call_decline_background", 4.5);
            check(colors, "call_icon_on_control", "call_end_background", 4.5);
        }
    }

    private static void load(Map<String, String> colors, String dir) throws Exception {
        try (var files = Files.list(Path.of("src/main/res", dir))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                NodeList entries = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(path.toFile()).getElementsByTagName("color");
                for (int i = 0; i < entries.getLength(); i++) {
                    Element e = (Element) entries.item(i);
                    colors.put(e.getAttribute("name"), e.getTextContent().trim());
                }
            }
        }
    }

    private static void check(Map<String, String> colors, String fg, String bg, double minimum) {
        double a = luminance(resolve(colors, fg)), b = luminance(resolve(colors, bg));
        double ratio = (Math.max(a,b) + .05) / (Math.min(a,b) + .05);
        assertTrue(fg + " on " + bg + " contrast=" + ratio, ratio >= minimum);
    }
    private static long resolve(Map<String, String> colors, String name) {
        String value = colors.get(name);
        if (value.startsWith("@color/")) return resolve(colors, value.substring(7));
        return Long.parseLong(value.substring(1), 16);
    }
    private static double luminance(long color) {
        double[] rgb = {(color >> 16) & 255, (color >> 8) & 255, color & 255};
        for (int i=0; i<3; i++) {
            double c = rgb[i] / 255;
            rgb[i] = c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
        }
        return .2126 * rgb[0] + .7152 * rgb[1] + .0722 * rgb[2];
    }
}
