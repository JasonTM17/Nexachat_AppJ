package com.nexachat.core.ui.components;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public final class AvatarPhotoLoadingRulesTest {

    @Test
    public void nonBlankPhotoUrlRequiresCircleCrop() {
        assertTrue(AvatarPhotoLoadingRules.useCircleCropForPhotoUrl("/media/u/1.jpg"));
    }

    @Test
    public void missingPhotoUrlUsesInitialsOnly() {
        assertFalse(AvatarPhotoLoadingRules.useCircleCropForPhotoUrl(null));
        assertFalse(AvatarPhotoLoadingRules.useCircleCropForPhotoUrl(""));
        assertFalse(AvatarPhotoLoadingRules.useCircleCropForPhotoUrl("   "));
    }

    @Test
    public void avatarViewWiresCircleCropForLoadedPhotos() throws Exception {
        File avatarView = resolveAvatarViewSource();
        String source = new String(Files.readAllBytes(avatarView.toPath()), StandardCharsets.UTF_8);
        assertTrue("AvatarView must consult AvatarPhotoLoadingRules", source.contains("AvatarPhotoLoadingRules"));
        assertTrue("AvatarView must circle-crop loaded photos", source.contains(".circleCrop()"));
    }

    private static File resolveAvatarViewSource() {
        File[] candidates = new File[] {
                new File("src/main/java/com/nexachat/core/ui/components/AvatarView.java"),
                new File("app/src/main/java/com/nexachat/core/ui/components/AvatarView.java"),
                new File("android/app/src/main/java/com/nexachat/core/ui/components/AvatarView.java")
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("AvatarView.java not found from unit-test working directory");
    }
}
