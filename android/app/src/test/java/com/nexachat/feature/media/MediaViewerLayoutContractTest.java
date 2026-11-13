package com.nexachat.feature.media;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void loadingAndErrorHostsUseReadableSurfacesBelowToolbar() throws Exception {
        for (String id : new String[] {"mediaLoadingHost", "mediaErrorHost"}) {
            Element host = view("layout/fragment_media_viewer.xml", id);
            assertEquals("@color/colorSurface", host.getAttributeNS(ANDROID, "background"));
            assertEquals("?attr/actionBarSize",
                    host.getAttributeNS(ANDROID, "layout_marginTop"));
        }
    }

    @Test
    public void imageFileActionsWaitForDecodeAndRetryCanRecover() {
        MediaViewerFragment.ImageLoadState state = new MediaViewerFragment.ImageLoadState();
        assertFalse(state.canUseFileActions());
        assertFalse(state.shouldShowRetry());

        long firstGeneration = state.begin();
        assertFalse(state.canUseFileActions());
        assertFalse(state.shouldShowRetry());

        state.fail();
        assertFalse(state.canUseFileActions());
        assertTrue(state.shouldShowRetry());

        long retryGeneration = state.begin();
        assertFalse(state.isCurrent(firstGeneration));
        assertTrue(state.isCurrent(retryGeneration));
        assertFalse(state.canUseFileActions());
        assertFalse(state.shouldShowRetry());
        state.decodeSucceeded();
        assertTrue(state.canUseFileActions());
        assertFalse(state.shouldShowRetry());

        state.reset();
        assertFalse(state.isCurrent(retryGeneration));
        assertFalse(state.canUseFileActions());
    }
}
