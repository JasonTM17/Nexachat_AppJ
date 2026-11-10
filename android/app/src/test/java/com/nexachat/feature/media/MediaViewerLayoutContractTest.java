package com.nexachat.feature.media;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import static org.junit.Assert.assertEquals;

/** Layout contract for accessible media viewer photo zoom. */
public final class MediaViewerLayoutContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private static Element view(String file, String id) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList nodes = factory.newDocumentBuilder().parse(Path.of("src/main/res", file).toFile())
                .getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            if (("@+id/" + id).equals(node.getAttributeNS(ANDROID, "id"))) {
                return node;
            }
        }
        throw new AssertionError("Missing " + id + " in " + file);
    }

    @Test
    public void fullScreenPhotoUsesZoomContentDescription() throws Exception {
        Element image = view("layout/fragment_media_viewer.xml", "mediaImage");
        assertEquals("@string/cd_media_photo_zoom",
                image.getAttributeNS(ANDROID, "contentDescription"));
    }
}
