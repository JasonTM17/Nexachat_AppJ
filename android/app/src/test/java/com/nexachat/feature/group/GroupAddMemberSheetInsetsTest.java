package com.nexachat.feature.group;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class GroupAddMemberSheetInsetsTest {

    @Test
    public void addMemberSheetAppliesImeInsetsInFragment() throws Exception {
        Path src = locate("com/nexachat/feature/group/AddMemberSheetFragment.java");
        String body = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        assertTrue(body.contains("applySheetInsets"));

        Path host = locate("com/nexachat/feature/group/GroupDetailFragment.java");
        String hostBody = new String(Files.readAllBytes(host), StandardCharsets.UTF_8);
        assertTrue(hostBody.contains("AddMemberSheetFragment.TAG"));

        Path insets = locate("com/nexachat/feature/ui/ImeInsets.java");
        String insetsBody = new String(Files.readAllBytes(insets), StandardCharsets.UTF_8);
        assertTrue(insetsBody.contains("WindowInsetsCompat.Type.ime()"));
    }

    private static Path locate(String relative) {
        Path[] candidates = new Path[] {
                Path.of("src/main/java", relative),
                Path.of("app/src/main/java", relative),
                Path.of("android/app/src/main/java", relative),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Missing " + relative);
    }
}
