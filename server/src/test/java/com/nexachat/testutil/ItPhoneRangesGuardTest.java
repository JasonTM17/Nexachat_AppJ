package com.nexachat.testutil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ItPhoneRangesGuardTest {

    private static final String SEARCH_TRIGRAM = "com.nexachat.message.SearchTrigramIndexIT";
    /** {@code AskApiIT} inserts this stem and is owned by brief 375. */
    private static final String ASK_API_STEM = "+849";
    private static final Pattern INLINE_PREFIX = Pattern.compile(
            "(?m)\\b(?:PHONE_PREFIX|PHONE_RANGE)\\s*=\\s*\"\\+84[0-9]*\"");
    private static final Pattern INLINE_GENERATOR = Pattern.compile(
            "(?m)\\bString\\s+\\w*[Pp]hone\\w*\\s*=\\s*\"\\+84[0-9]*\"\\s*\\+");
    private static final Pattern INLINE_CLEANUP = Pattern.compile(
            "(?i)phone\\s+like\\s+'\\+84[0-9]*%'");
    private static final Pattern USER_FIXTURE = Pattern.compile(
            "(?s)\\+84[0-9]+|User\\.create\\s*\\(|INSERT\\s+INTO\\s+users\\b|\\bregister(?:User)?\\s*\\(");

    @Test
    void registeredRangesAreUniqueAndNonOverlapping() {
        Map<String, String> prefixes = ItPhoneRanges.registeredPrefixes();
        assertThat(prefixes).containsEntry(SEARCH_TRIGRAM, "+8493");

        List<Map.Entry<String, String>> entries = new ArrayList<>(prefixes.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, String> entry : entries) {
            if (SEARCH_TRIGRAM.equals(entry.getKey())) {
                continue;
            }
            assertThat(entry.getValue().startsWith(ASK_API_STEM))
                    .as("%s=%s overlaps AskApiIT reserved stem %s",
                            entry.getKey(), entry.getValue(), ASK_API_STEM)
                    .isFalse();
        }
        for (int left = 0; left < entries.size(); left++) {
            for (int right = left + 1; right < entries.size(); right++) {
                Map.Entry<String, String> first = entries.get(left);
                Map.Entry<String, String> second = entries.get(right);
                boolean overlaps = first.getValue().startsWith(second.getValue())
                        || second.getValue().startsWith(first.getValue());
                assertThat(overlaps)
                        .as("phone ranges overlap: %s=%s, %s=%s", first.getKey(), first.getValue(),
                                second.getKey(), second.getValue())
                        .isFalse();
            }
        }
    }

    @Test
    void integrationTestsDoNotDeclareUnregisteredPhoneRanges() throws IOException {
        Path sourceRoot = Path.of("src/test/java");
        assertThat(Files.isDirectory(sourceRoot)).as("Maven test source root exists").isTrue();

        Map<String, String> prefixes = ItPhoneRanges.registeredPrefixes();
        for (Map.Entry<String, String> entry : prefixes.entrySet()) {
            Path source = sourceRoot.resolve(entry.getKey().replace('.', '/') + ".java");
            assertThat(Files.isRegularFile(source))
                    .as("registered integration test source exists: %s", entry.getKey())
                    .isTrue();
            if (!SEARCH_TRIGRAM.equals(entry.getKey())) {
                assertThat(Files.readString(source))
                        .as("registered integration test uses the shared range helper: %s", entry.getKey())
                        .contains("ItPhoneRanges.");
            }
        }

        List<Path> sources;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            sources = stream.filter(path -> path.getFileName().toString().endsWith("IT.java"))
                    .sorted(Comparator.naturalOrder()).toList();
        }
        for (Path source : sources) {
            String simpleName = source.getFileName().toString();
            if (simpleName.equals("AskApiIT.java") || simpleName.equals("SearchTrigramIndexIT.java")) {
                continue;
            }
            String contents = Files.readString(source);
            String owner = sourceRoot.relativize(source).toString().replace('\\', '/');
            owner = owner.substring(0, owner.length() - ".java".length()).replace('/', '.');
            if (!owner.equals("com.nexachat.rag.AskApiIT")
                    && USER_FIXTURE.matcher(contents).find()) {
                assertThat(prefixes).containsKey(owner);
            }
            assertThat(INLINE_PREFIX.matcher(contents).find())
                    .as("phone prefix constants must use the shared registry: %s", source)
                    .isFalse();
            assertThat(INLINE_GENERATOR.matcher(contents).find())
                    .as("phone generators must use the shared registry: %s", source)
                    .isFalse();
            assertThat(INLINE_CLEANUP.matcher(contents).find())
                    .as("phone-prefix cleanup must use the shared range helper: %s", source)
                    .isFalse();
        }
    }
}
