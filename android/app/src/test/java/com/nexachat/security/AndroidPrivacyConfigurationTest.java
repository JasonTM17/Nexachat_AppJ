package com.nexachat.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Static contract tests for manifest backup and network privacy boundaries. */
public final class AndroidPrivacyConfigurationTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final List<String> APP_DATA_DOMAINS = List.of(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref");

    @Test
    public void manifestWiresLegacyAndModernBackupRules() throws Exception {
        Element application = applicationElement();

        assertEquals("false", application.getAttributeNS(ANDROID_NS, "allowBackup"));
        assertEquals("@xml/backup_rules",
                application.getAttributeNS(ANDROID_NS, "fullBackupContent"));
        assertEquals("@xml/data_extraction_rules",
                application.getAttributeNS(ANDROID_NS, "dataExtractionRules"));
    }

    @Test
    public void legacyBackupRulesExcludePrivateStorage() throws Exception {
        Document rules = parse(requiredFile(
                "res/xml/backup_rules.xml", new File(appSourceRoot(), "res/xml/backup_rules.xml")));

        assertEquals("full-backup-content", rules.getDocumentElement().getTagName());
        assertEveryPrivateDomainExcluded(rules.getDocumentElement());
    }

    @Test
    public void cloudAndDeviceTransferRulesExcludePrivateStorage() throws Exception {
        Document rules = parse(requiredFile(
                "res/xml/data_extraction_rules.xml",
                new File(appSourceRoot(), "res/xml/data_extraction_rules.xml")));
        Element root = rules.getDocumentElement();
        assertEquals("data-extraction-rules", root.getTagName());

        Element cloudBackup = (Element) root.getElementsByTagName("cloud-backup").item(0);
        Element deviceTransfer = (Element) root.getElementsByTagName("device-transfer").item(0);
        assertNotNull("cloud backup rules must be explicit", cloudBackup);
        assertNotNull("device-to-device transfer rules must be explicit", deviceTransfer);
        assertEveryPrivateDomainExcluded(cloudBackup);
        assertEveryPrivateDomainExcluded(deviceTransfer);
    }

    @Test
    public void releaseNetworkConfigDisablesCleartextAndTrustsOnlySystemCertificates()
            throws Exception {
        Element application = applicationElement();
        assertEquals("@xml/network_security_config",
                application.getAttributeNS(ANDROID_NS, "networkSecurityConfig"));

        Document network = parse(requiredFile(
                "res/xml/network_security_config.xml",
                new File(appSourceRoot(), "res/xml/network_security_config.xml")));
        Element base = (Element) network.getDocumentElement()
                .getElementsByTagName("base-config").item(0);
        assertNotNull("release must define a base network policy", base);
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"));

        NodeList anchors = base.getElementsByTagName("trust-anchors");
        assertEquals("release trust anchors must be explicit", 1, anchors.getLength());
        NodeList certificates = ((Element) anchors.item(0)).getElementsByTagName("certificates");
        assertEquals("only the platform system CA store is trusted", 1, certificates.getLength());
        assertEquals("system", ((Element) certificates.item(0)).getAttribute("src"));
    }

    @Test
    public void cleartextOverrideExistsOnlyInDebugResources() throws Exception {
        File debugConfig = requiredFile("debug/res/xml/network_security_config.xml",
                new File(appSourceRoot().getParentFile(),
                        "debug/res/xml/network_security_config.xml"));
        Document network = parse(debugConfig);
        Element base = (Element) network.getDocumentElement()
                .getElementsByTagName("base-config").item(0);
        assertNotNull(base);
        assertEquals("true", base.getAttribute("cleartextTrafficPermitted"));
        assertTrue("debug override stays in the debug source set",
                debugConfig.getPath().contains("debug" + File.separator + "res"));
    }

    @Test
    public void manifestComponentsAreExplicitAndNoExternalDeepLinksAreDeclared()
            throws Exception {
        Element application = applicationElement();
        String[] componentTags = {"activity", "service", "receiver", "provider"};
        int exportedCount = 0;
        for (String tag : componentTags) {
            NodeList components = application.getElementsByTagName(tag);
            for (int i = 0; i < components.getLength(); i++) {
                Element component = (Element) components.item(i);
                String name = component.getAttributeNS(ANDROID_NS, "name");
                String exported = component.getAttributeNS(ANDROID_NS, "exported");
                assertTrue(tag + " must declare android:exported: " + name,
                        "true".equals(exported) || "false".equals(exported));
                if ("true".equals(exported)) {
                    exportedCount++;
                    assertEquals("only the launcher activity is externally reachable",
                            ".MainActivity", name);
                }
            }
        }
        assertEquals(1, exportedCount);

        assertEquals("no VIEW deep links are declared", 0,
                application.getElementsByTagName("data").getLength());
        NodeList actions = application.getElementsByTagName("action");
        for (int i = 0; i < actions.getLength(); i++) {
            Element action = (Element) actions.item(i);
            assertTrue("no browsable external action is declared",
                    !"android.intent.action.VIEW".equals(
                            action.getAttributeNS(ANDROID_NS, "name")));
        }
        NodeList categories = application.getElementsByTagName("category");
        for (int i = 0; i < categories.getLength(); i++) {
            Element category = (Element) categories.item(i);
            assertTrue("no browsable external category is declared",
                    !"android.intent.category.BROWSABLE".equals(
                            category.getAttributeNS(ANDROID_NS, "name")));
        }
    }

    private static void assertEveryPrivateDomainExcluded(Element ruleSet) {
        for (String domain : APP_DATA_DOMAINS) {
            boolean excluded = false;
            NodeList rules = ruleSet.getElementsByTagName("exclude");
            for (int i = 0; i < rules.getLength(); i++) {
                Element rule = (Element) rules.item(i);
                if (domain.equals(rule.getAttribute("domain"))
                        && ".".equals(rule.getAttribute("path"))) {
                    excluded = true;
                    break;
                }
            }
            assertTrue("backup rules must exclude all files in domain " + domain, excluded);
        }
    }

    private static Element applicationElement() throws Exception {
        Document manifest = parse(requiredFile(
                "AndroidManifest.xml", new File(appSourceRoot(), "AndroidManifest.xml")));
        NodeList applications = manifest.getElementsByTagName("application");
        assertEquals(1, applications.getLength());
        return (Element) applications.item(0);
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(file);
    }

    private static File requiredFile(String label, File file) {
        assertTrue(label + " must exist", file.isFile());
        return file;
    }

    private static File appSourceRoot() {
        File[] candidates = {
                new File("src/main"),
                new File("app/src/main"),
                new File("android/app/src/main"),
                new File("../app/src/main")
        };
        for (File candidate : candidates) {
            if (new File(candidate, "AndroidManifest.xml").isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate android app src/main from "
                + new File(".").getAbsolutePath());
    }
}
