package com.nexachat.res;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Audit tests verifying accessibility and localization invariants on new auth and profile screens:
 * - Dynamic error banners have accessibilityLiveRegion="polite"
 * - High-contrast text colors on themed destructive buttons
 * - Password input icon consistency
 * - Vietnamese string polish for auth and settings
 */
public final class NewScreensAccessibilityUnitTest {

    private static final String NS_ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String NS_APP = "http://schemas.android.com/apk/res-auto";

    private File resDir;

    @Before
    public void setUp() {
        File[] candidates = new File[] {
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

    private Document loadXml(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    private Element findElementById(Node node, String idSuffix) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) node;
            String id = el.getAttributeNS(NS_ANDROID, "id");
            if (id == null || id.isEmpty()) {
                id = el.getAttribute("android:id");
            }
            if (id != null && id.endsWith("/" + idSuffix)) {
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

    private String getAttr(Element el, String ns, String localName) {
        String val = el.getAttributeNS(ns, localName);
        if (val == null || val.isEmpty()) {
            val = el.getAttribute(localName);
        }
        return val == null ? "" : val;
    }

    @Test
    public void errorBannersHaveAccessibilityLiveRegion() throws Exception {
        Map<String, String> layoutToErrorId = new HashMap<>();
        layoutToErrorId.put("fragment_login.xml", "errorBanner");
        layoutToErrorId.put("fragment_register.xml", "errorBanner");
        layoutToErrorId.put("fragment_profile.xml", "profileError");
        layoutToErrorId.put("fragment_recovery_email_form.xml", "recoveryFormError");
        layoutToErrorId.put("fragment_recovery_email_manage.xml", "recoveryEmailError");
        layoutToErrorId.put("fragment_recovery_email_otp.xml", "recoveryOtpError");

        for (Map.Entry<String, String> entry : layoutToErrorId.entrySet()) {
            File layoutFile = new File(resDir, "layout/" + entry.getKey());
            Document doc = loadXml(layoutFile);
            Element el = findElementById(doc.getDocumentElement(), entry.getValue());
            assertNotNull(entry.getKey() + " must contain @" + entry.getValue(), el);
            String liveRegion = getAttr(el, NS_ANDROID, "accessibilityLiveRegion");
            assertEquals(entry.getKey() + " error banner @" + entry.getValue()
                    + " must declare android:accessibilityLiveRegion=\"polite\"", "polite", liveRegion);
        }
    }

    @Test
    public void deleteAccountConfirmButtonHasOnErrorTextColor() throws Exception {
        File file = new File(resDir, "layout/fragment_delete_account.xml");
        Document doc = loadXml(file);
        Element button = findElementById(doc.getDocumentElement(), "confirmDelete");
        assertNotNull("fragment_delete_account.xml must contain confirmDelete", button);
        String textColor = getAttr(button, NS_ANDROID, "textColor");
        assertEquals("confirmDelete must declare android:textColor=\"?attr/colorOnError\"",
                "?attr/colorOnError", textColor);
    }

    @Test
    public void deleteAccountPasswordLayoutHasVisibilityIcon() throws Exception {
        File file = new File(resDir, "layout/fragment_delete_account.xml");
        Document doc = loadXml(file);
        Element layout = findElementById(doc.getDocumentElement(), "passwordLayout");
        assertNotNull("fragment_delete_account.xml must contain passwordLayout", layout);
        String endIcon = getAttr(layout, NS_APP, "endIconDrawable");
        assertEquals("passwordLayout must declare app:endIconDrawable=\"@drawable/ic_visibility\"",
                "@drawable/ic_visibility", endIcon);
    }

    @Test
    public void vietnameseStringsPolishedForNewScreens() throws Exception {
        File viStringsFile = new File(resDir, "values-vi/strings.xml");
        Document doc = loadXml(viStringsFile);
        Map<String, String> map = new HashMap<>();
        NodeList nodes = doc.getDocumentElement().getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            map.put(el.getAttribute("name"), el.getTextContent());
        }

        assertEquals("Phiên đã hết hạn, hãy đăng nhập lại.", map.get("session_expired_sign_in_again"));
        assertEquals("Mã 6 chữ số", map.get("recovery_email_otp_label"));
        assertEquals("Mã không đúng hoặc đã hết hạn", map.get("recovery_email_code_invalid"));
        assertEquals("Mã không đúng hoặc đã hết hạn", map.get("forgot_password_code_invalid"));
        assertEquals("Đổi số điện thoại", map.get("forgot_password_change_phone"));
        assertEquals("Hãy đặt mật khẩu trước khi gỡ phương thức đăng nhập này.", map.get("error_last_sign_in_method"));
    }
}
