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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * JVM unit test asserting string parity between values/strings.xml and values-vi/strings.xml.
 * Pure XML parsing without any Android platform dependencies.
 */
public final class StringParityUnitTest {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%(?:(\\d+)\\$)?([0-9]*\\.?[0-9]*[a-zA-Z%])");

    // Allowlist of string keys where Vietnamese value is intentionally identical to English
    // (brand names, numbers, acronyms, or format tokens).
    private static final Set<String> UNTRANSLATED_ALLOWLIST = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "app_name",
            "pinned_count",
            "voice_recording_duration_zero",
            "summary_badge_ai",
            "summary_citation_chip"
    )));

    // Common Vietnamese vowels with diacritics across the 5 tones and modified vowels (ă, â, đ, ê, ô, ơ, ư)
    private static final Pattern VIETNAMESE_DIACRITICS_PATTERN = Pattern.compile(
            "[àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ" +
            "ÀÁẢÃẠĂẰẮẲẴẶÂẦẤẨẪẬÈÉẺẼẸÊỀẾỂỄỆÌÍỈĨỊÒÓỎÕỌÔỒỐỔỖỘƠỜỚỞỠỢÙÚỦŨỤƯỪỨỬỮỰỲÝỶỸỴĐ]"
    );

    // Heuristic list of common Vietnamese words expected to have diacritics when translated
    private static final List<String> COMMON_VIETNAMESE_WORDS = Arrays.asList(
            "tin", "nhắn", "cuộc", "trò", "chuyện", "đăng", "nhập", "xuất", "tài", "khoản",
            "mật", "khẩu", "xác", "nhận", "thành", "viên", "liên", "hệ", "danh", "bạ",
            "tìm", "kiếm", "cài", "đặt", "ngôn", "ngữ", "thông", "báo", "gửi", "đã", "xem"
    );

    private File resDir;

    @Before
    public void setUp() {
        resDir = locateResDir();
    }

    private static File locateResDir() {
        File[] candidates = new File[] {
                new File("src/main/res"),
                new File("app/src/main/res"),
                new File("android/app/src/main/res"),
                new File("../app/src/main/res")
        };
        for (File candidate : candidates) {
            if (candidate.exists() && candidate.isDirectory() && new File(candidate, "values/strings.xml").exists()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate res directory from " + new File(".").getAbsolutePath());
    }

    private static final class ParsedStrings {
        final Map<String, String> translatableStrings = new LinkedHashMap<>();
        final Set<String> nonTranslatableKeys = new HashSet<>();
        final Map<String, Map<String, String>> plurals = new LinkedHashMap<>();
    }

    private ParsedStrings parseStringResourceFile(File file) throws Exception {
        ParsedStrings result = new ParsedStrings();
        if (!file.exists()) {
            return result;
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(file);
        Element root = doc.getDocumentElement();

        NodeList stringNodes = root.getElementsByTagName("string");
        for (int i = 0; i < stringNodes.getLength(); i++) {
            Element el = (Element) stringNodes.item(i);
            String name = el.getAttribute("name");
            String translatable = el.getAttribute("translatable");
            String text = el.getTextContent();

            if ("false".equalsIgnoreCase(translatable)) {
                result.nonTranslatableKeys.add(name);
            } else {
                result.translatableStrings.put(name, text);
            }
        }

        NodeList pluralNodes = root.getElementsByTagName("plurals");
        for (int i = 0; i < pluralNodes.getLength(); i++) {
            Element el = (Element) pluralNodes.item(i);
            String name = el.getAttribute("name");
            Map<String, String> items = new LinkedHashMap<>();
            NodeList itemNodes = el.getElementsByTagName("item");
            for (int j = 0; j < itemNodes.getLength(); j++) {
                Element itemEl = (Element) itemNodes.item(j);
                items.put(itemEl.getAttribute("quantity"), itemEl.getTextContent());
            }
            result.plurals.put(name, items);
        }

        return result;
    }

    @Test
    public void testStringParity_identicalKeySets() throws Exception {
        File enFile = new File(resDir, "values/strings.xml");
        File viFile = new File(resDir, "values-vi/strings.xml");

        ParsedStrings en = parseStringResourceFile(enFile);
        ParsedStrings vi = parseStringResourceFile(viFile);

        Set<String> missingInVi = new HashSet<>(en.translatableStrings.keySet());
        missingInVi.removeAll(vi.translatableStrings.keySet());

        Set<String> extraInVi = new HashSet<>(vi.translatableStrings.keySet());
        extraInVi.removeAll(en.translatableStrings.keySet());

        assertTrue("Keys present in EN but missing in VI: " + missingInVi, missingInVi.isEmpty());
        assertTrue("Keys present in VI but missing in EN: " + extraInVi, extraInVi.isEmpty());
        assertEquals("Translatable string key count mismatch",
                en.translatableStrings.keySet().size(), vi.translatableStrings.keySet().size());
    }

    @Test
    public void testStringParity_translatableFalseUsage() throws Exception {
        File enFile = new File(resDir, "values/strings.xml");
        File viFile = new File(resDir, "values-vi/strings.xml");

        ParsedStrings en = parseStringResourceFile(enFile);
        ParsedStrings vi = parseStringResourceFile(viFile);

        assertFalse("values/strings.xml must have non-translatable entries defined", en.nonTranslatableKeys.isEmpty());

        for (String nonTranslatableKey : en.nonTranslatableKeys) {
            assertFalse("Non-translatable key '" + nonTranslatableKey + "' should not be present as translatable in values-vi/strings.xml",
                    vi.translatableStrings.containsKey(nonTranslatableKey));
            assertTrue("Non-translatable key '" + nonTranslatableKey + "' when present in values-vi must preserve translatable='false'",
                    vi.nonTranslatableKeys.contains(nonTranslatableKey));
        }
    }

    @Test
    public void testStringParity_identicalPlaceholders() throws Exception {
        File enFile = new File(resDir, "values/strings.xml");
        File viFile = new File(resDir, "values-vi/strings.xml");

        ParsedStrings en = parseStringResourceFile(enFile);
        ParsedStrings vi = parseStringResourceFile(viFile);

        for (Map.Entry<String, String> entry : en.translatableStrings.entrySet()) {
            String key = entry.getKey();
            String enVal = entry.getValue();
            String viVal = vi.translatableStrings.get(key);
            assertNotNull("Missing Vietnamese translation for key: " + key, viVal);

            List<String> enPlaceholders = extractPlaceholders(enVal);
            List<String> viPlaceholders = extractPlaceholders(viVal);

            assertEquals("Placeholder mismatch for key: " + key, enPlaceholders, viPlaceholders);
        }
    }

    private static List<String> extractPlaceholders(String text) {
        List<String> placeholders = new ArrayList<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            placeholders.add(matcher.group());
        }
        return placeholders;
    }

    @Test
    public void testStringParity_pluralsQuantityRequirements() throws Exception {
        File enFile = new File(resDir, "values/strings.xml");
        File viFile = new File(resDir, "values-vi/strings.xml");

        ParsedStrings en = parseStringResourceFile(enFile);
        ParsedStrings vi = parseStringResourceFile(viFile);

        assertEquals("Plurals keys mismatch", en.plurals.keySet(), vi.plurals.keySet());

        for (String pluralKey : en.plurals.keySet()) {
            Map<String, String> enItems = en.plurals.get(pluralKey);
            Map<String, String> viItems = vi.plurals.get(pluralKey);

            assertTrue("EN plurals must have 'other'", enItems.containsKey("other"));
            assertTrue("VI plurals must have 'other'", viItems.containsKey("other"));
        }
    }

    @Test
    public void testStringParity_noEmptyValues() throws Exception {
        File enFile = new File(resDir, "values/strings.xml");
        File viFile = new File(resDir, "values-vi/strings.xml");

        ParsedStrings en = parseStringResourceFile(enFile);
        ParsedStrings vi = parseStringResourceFile(viFile);

        for (Map.Entry<String, String> e : en.translatableStrings.entrySet()) {
            assertFalse("Empty English value for key: " + e.getKey(), e.getValue().trim().isEmpty());
        }
        for (Map.Entry<String, String> e : vi.translatableStrings.entrySet()) {
            assertFalse("Empty Vietnamese value for key: " + e.getKey(), e.getValue().trim().isEmpty());
        }
    }

    @Test
    public void testStringParity_noUntranslatedCopies() throws Exception {
        File enFile = new File(resDir, "values/strings.xml");
        File viFile = new File(resDir, "values-vi/strings.xml");

        ParsedStrings en = parseStringResourceFile(enFile);
        ParsedStrings vi = parseStringResourceFile(viFile);

        List<String> identicalCopies = new ArrayList<>();
        for (Map.Entry<String, String> entry : en.translatableStrings.entrySet()) {
            String key = entry.getKey();
            if (UNTRANSLATED_ALLOWLIST.contains(key)) {
                continue;
            }
            String enVal = entry.getValue();
            String viVal = vi.translatableStrings.get(key);
            if (enVal != null && enVal.equals(viVal)) {
                identicalCopies.add(key + "='" + enVal + "'");
            }
        }

        assertTrue("Untranslated copies found (value matches EN without being in allowlist): " + identicalCopies,
                identicalCopies.isEmpty());
    }

    @Test
    public void testStringParity_noApostropheOrEscapeErrors() throws Exception {
        File enFile = new File(resDir, "values/strings.xml");
        File viFile = new File(resDir, "values-vi/strings.xml");

        verifyApostrophesAndEscapes(enFile);
        verifyApostrophesAndEscapes(viFile);
    }

    private void verifyApostrophesAndEscapes(File file) throws Exception {
        List<String> lines = java.nio.file.Files.readAllLines(file.toPath());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int start = line.indexOf('>');
            int end = line.lastIndexOf("</string>");
            if (start != -1 && end > start && line.contains("<string")) {
                String text = line.substring(start + 1, end);
                if (text.startsWith("\"") && text.endsWith("\"")) {
                    continue;
                }
                for (int j = 0; j < text.length(); j++) {
                    if (text.charAt(j) == '\'') {
                        if (j == 0 || text.charAt(j - 1) != '\\') {
                            fail("Unescaped apostrophe in " + file.getName() + " line " + (i + 1) + ": " + line);
                        }
                    }
                }
                if (text.contains("\\n") && text.contains("\n")) {
                    fail("Stray unescaped newline in " + file.getName() + " line " + (i + 1) + ": " + line);
                }
            }
        }
    }

    @Test
    public void testStringParity_vietnameseDiacriticsPresence() throws Exception {
        File viFile = new File(resDir, "values-vi/strings.xml");
        ParsedStrings vi = parseStringResourceFile(viFile);

        List<String> missingDiacritics = new ArrayList<>();
        for (Map.Entry<String, String> entry : vi.translatableStrings.entrySet()) {
            String key = entry.getKey();
            if (UNTRANSLATED_ALLOWLIST.contains(key)) {
                continue;
            }
            String text = entry.getValue();

            // Check if string has at least one Vietnamese diacritic character
            // or is purely an acronym / toneless word (like "Xem", "Ghim")
            boolean hasDiacritics = VIETNAMESE_DIACRITICS_PATTERN.matcher(text).find();
            if (!hasDiacritics) {
                // Check if it's one of the known toneless single words in Vietnamese ("Ghim", "Xem")
                if (!text.equals("Ghim") && !text.equals("Xem")) {
                    missingDiacritics.add(key + "='" + text + "'");
                }
            }
        }

        assertTrue("Vietnamese values expected to have diacritics but missing: " + missingDiacritics,
                missingDiacritics.isEmpty());
    }

    @Test
    public void testCallStringsParity() throws Exception {
        File enFile = new File(resDir, "values/strings_call.xml");
        File viFile = new File(resDir, "values-vi/strings_call.xml");

        ParsedStrings en = parseStringResourceFile(enFile);
        ParsedStrings vi = parseStringResourceFile(viFile);

        assertEquals("Call strings key count mismatch", en.translatableStrings.keySet(), vi.translatableStrings.keySet());
        for (String key : en.translatableStrings.keySet()) {
            assertEquals("Call placeholder mismatch for key: " + key,
                    extractPlaceholders(en.translatableStrings.get(key)),
                    extractPlaceholders(vi.translatableStrings.get(key)));
        }
    }

    @Test
    public void testSummaryStringsParity() throws Exception {
        File enFile = new File(resDir, "values/strings_summary.xml");
        File viFile = new File(resDir, "values-vi/strings_summary.xml");

        ParsedStrings en = parseStringResourceFile(enFile);
        ParsedStrings vi = parseStringResourceFile(viFile);

        assertEquals("Summary strings key count mismatch", en.translatableStrings.keySet(), vi.translatableStrings.keySet());
        for (String key : en.translatableStrings.keySet()) {
            assertEquals("Summary placeholder mismatch for key: " + key,
                    extractPlaceholders(en.translatableStrings.get(key)),
                    extractPlaceholders(vi.translatableStrings.get(key)));
        }
    }
}
