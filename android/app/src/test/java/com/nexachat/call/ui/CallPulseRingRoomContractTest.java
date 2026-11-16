package com.nexachat.call.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The call screens scale the pulse ring up to 1.18x. Its frame must be large
 * enough for the full animation, otherwise the circle is clipped to a square.
 */
public final class CallPulseRingRoomContractTest {

    private static final float MAX_SCALE = 1.18f;

    @Test
    public void everyPulseRingFrameHasRoomForTheFullScale() throws Exception {
        String[][] rings = {
                {"layout", "activity_call.xml", "call_pulse_ring"},
                {"layout-land", "activity_call.xml", "call_pulse_ring"},
                {"layout", "activity_incoming_call.xml", "incoming_pulse_ring"},
        };
        for (String[] ring : rings) {
            Path file = Path.of("src/main/res", ring[0], ring[1]);
            assertTrue(file + " must exist", Files.exists(file));
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(file.toFile());
            Element view = byId(document, "@+id/" + ring[2]);
            Element frame = (Element) view.getParentNode();
            float ringSize = dp(view.getAttribute("android:layout_width"));
            assertEquals(file + " ring is square", ringSize,
                    dp(view.getAttribute("android:layout_height")), 0f);
            float frameSize = dp(frame.getAttribute("android:layout_width"));
            assertTrue(file + " frame " + frameSize + "dp must fit the ring at " + MAX_SCALE + "x ("
                            + ringSize * MAX_SCALE + "dp)",
                    frameSize >= ringSize * MAX_SCALE);
            assertEquals(file + " frame is square", frameSize,
                    dp(frame.getAttribute("android:layout_height")), 0f);
        }
    }

    private static Element byId(Document document, String id) {
        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (id.equals(element.getAttribute("android:id"))) {
                return element;
            }
        }
        throw new AssertionError("missing " + id);
    }

    private static float dp(String value) {
        assertTrue("expected a dp value but was '" + value + "'", value.endsWith("dp"));
        return Float.parseFloat(value.substring(0, value.length() - 2));
    }
}
