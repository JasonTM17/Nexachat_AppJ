package com.nexachat.feature.summary;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class SummaryTopicsSectionTest {

    @Test
    public void topicsSectionUsesWrappingChipGroup() throws Exception {
        Path src = locateSource("SummaryTopicsSection.java");
        String body = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        assertTrue(body.contains("setSingleLine(false)"));
        assertTrue(body.contains("ChipGroup"));
    }

    private static Path locateSource(String name) {
        Path[] candidates = new Path[] {
                Path.of("src/main/java/com/nexachat/feature/summary", name),
                Path.of("app/src/main/java/com/nexachat/feature/summary", name),
                Path.of("android/app/src/main/java/com/nexachat/feature/summary", name),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Missing " + name);
    }
}
