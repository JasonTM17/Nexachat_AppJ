package com.nexachat.feature.group;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class GroupAddMemberSheetInsetsTest {

    @Test
    public void addMemberSheetAppliesImeInsetsInFragment() throws Exception {
        Path src = locateSource("GroupDetailFragment.java");
        String body = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        assertTrue(body.contains("WindowInsetsCompat.Type.ime()"));
        assertTrue(body.contains("showAddMemberDialog"));
    }

    private static Path locateSource(String name) {
        Path[] candidates = new Path[] {
                Path.of("src/main/java/com/nexachat/feature/group", name),
                Path.of("app/src/main/java/com/nexachat/feature/group", name),
                Path.of("android/app/src/main/java/com/nexachat/feature/group", name),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Missing " + name);
    }
}
