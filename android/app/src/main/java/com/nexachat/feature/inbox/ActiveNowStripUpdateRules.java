package com.nexachat.feature.inbox;

import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Decides when the Active Now horizontal strip needs a structural child rebuild. */
public final class ActiveNowStripUpdateRules {

    private ActiveNowStripUpdateRules() {}

    public static boolean needsStructuralRebuild(
            @NonNull List<String> previousPeerKeys, @NonNull List<String> nextPeerKeys) {
        return !Objects.equals(previousPeerKeys, nextPeerKeys);
    }
}
