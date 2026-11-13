package com.nexachat.core.ui.components;

import androidx.annotation.Nullable;

/** Glide loading rules for {@link AvatarView} photo layer (initials fallback unchanged). */
public final class AvatarPhotoLoadingRules {

    private AvatarPhotoLoadingRules() { }

    /**
     * A square {@link android.widget.ImageView} with center-crop alone does not mask non-square
     * photos to the avatar circle; Glide circle-crop is required for loaded bitmaps.
     */
    public static boolean useCircleCropForPhotoUrl(@Nullable String avatarUrl) {
        return avatarUrl != null && !avatarUrl.isBlank();
    }
}
