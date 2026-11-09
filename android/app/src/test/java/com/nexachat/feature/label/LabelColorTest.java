package com.nexachat.feature.label;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public final class LabelColorTest {
    @Test
    public void supportedColorIdsMatchContract() {
        assertEquals(Set.of("INDIGO", "BLUE", "CYAN", "TEAL", "EMERALD", "AMBER", "ROSE", "PURPLE"),
                java.util.Arrays.stream(LabelColor.ids()).collect(Collectors.toSet()));
    }

    @Test
    public void whiteLabelTextMeetsContrastForEveryThemeBackground() {
        for (LabelColor color : LabelColor.values()) {
            double contrast = 1.05 / (relativeLuminance(color.getBackgroundColor()) + 0.05);
            assertTrue(color.name() + " contrast was " + contrast, contrast >= 4.5);
        }
    }

    private static double relativeLuminance(int argb) {
        double red = linearChannel((argb >>> 16) & 0xFF);
        double green = linearChannel((argb >>> 8) & 0xFF);
        double blue = linearChannel(argb & 0xFF);
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    private static double linearChannel(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
