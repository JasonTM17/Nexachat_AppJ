package com.nexachat.core.ui;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

/** Guards the deferred navigation path from dropping the session-expired reason. */
public final class NexaHostActivitySessionExpiredNavigationTest {

    @Test
    public void deferredNavigationCarriesSessionExpiredReasonUntilNavigationSucceeds()
            throws Exception {
        String source = new String(
                Files.readAllBytes(findSource().toPath()), StandardCharsets.UTF_8);

        assertTrue(source.contains("navigateToLoginIfPossible(pendingSessionExpired)"));
        assertTrue(source.contains("pendingSessionExpired = pendingSessionExpired || sessionExpired"));
        assertTrue(source.contains("pendingSessionExpired = false"));
    }

    private static File findSource() {
        List<File> candidates = List.of(
                new File("src/main/java/com/nexachat/core/ui/NexaHostActivity.java"),
                new File("app/src/main/java/com/nexachat/core/ui/NexaHostActivity.java"),
                new File("android/app/src/main/java/com/nexachat/core/ui/NexaHostActivity.java"),
                new File("../app/src/main/java/com/nexachat/core/ui/NexaHostActivity.java"));
        return candidates.stream()
                .filter(File::isFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Could not locate NexaHostActivity source"));
    }
}
