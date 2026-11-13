package com.nexachat.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class InviteLayoutsAccessibilityUnitTest {

  private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";

  private File resDir;

  @Before
  public void setUp() {
    File[] candidates =
        new File[] {
          new File("src/main/res"),
          new File("app/src/main/res"),
          new File("android/app/src/main/res"),
          new File("../app/src/main/res")
        };
    for (File candidate : candidates) {
      if (candidate.isDirectory() && new File(candidate, "values/colors_nexachat.xml").isFile()) {
        resDir = candidate;
        return;
      }
    }
    throw new IllegalStateException("Could not locate res directory");
  }

  @Test
  public void inviteStatusTextViewsUsePoliteLiveRegions() throws Exception {
    Map<String, String[]> layoutToIds = new LinkedHashMap<>();
    layoutToIds.put("fragment_invite_join.xml", new String[] {"statusText"});
    layoutToIds.put(
        "fragment_invite_manage.xml", new String[] {"linkStatus", "errorText", "emptyRequests"});

    for (Map.Entry<String, String[]> entry : layoutToIds.entrySet()) {
      Document doc =
          DocumentBuilderFactory.newInstance()
              .newDocumentBuilder()
              .parse(new File(resDir, "layout/" + entry.getKey()));
      for (String id : entry.getValue()) {
        Element el = findElementById(doc.getDocumentElement(), id);
        assertNotNull(entry.getKey() + " must contain @" + id, el);
        assertEquals(
            entry.getKey() + " @" + id + " must declare android:accessibilityLiveRegion=\"polite\"",
            "polite",
            getAttr(el, NS_ANDROID, "accessibilityLiveRegion"));
      }
    }
  }

  private static Element findElementById(Node node, String idSuffix) {
    if (node.getNodeType() == Node.ELEMENT_NODE) {
      Element el = (Element) node;
      String id = el.getAttributeNS(NS_ANDROID, "id");
      if (id == null || id.isEmpty()) {
        id = el.getAttribute("android:id");
      }
      if (("@+id/" + idSuffix).equals(id) || id != null && id.endsWith("/" + idSuffix)) {
        return el;
      }
    }
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Element match = findElementById(children.item(i), idSuffix);
      if (match != null) {
        return match;
      }
    }
    return null;
  }

  private static String getAttr(Element el, String ns, String localName) {
    String val = el.getAttributeNS(ns, localName);
    if (val == null || val.isEmpty()) {
      val = el.getAttribute("android:" + localName);
    }
    if (val == null || val.isEmpty()) {
      val = el.getAttribute(localName);
    }
    return val == null ? "" : val;
  }
}
