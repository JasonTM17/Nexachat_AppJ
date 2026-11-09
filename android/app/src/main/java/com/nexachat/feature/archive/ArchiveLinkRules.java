package com.nexachat.feature.archive;

import androidx.annotation.Nullable;
import com.nexachat.core.ui.components.LinkUtils;
import java.net.URI;
import java.util.Locale;

/** Link policy for archive actions; only navigable HTTPS URLs may leave the app. */
public final class ArchiveLinkRules {

    private static final int MAX_URL_LENGTH = 4096;

    private ArchiveLinkRules() { }

    @Nullable
    public static String safeHttpsUrl(@Nullable String raw) {
        if (raw == null || raw.length() > MAX_URL_LENGTH) {
            return null;
        }
        String sanitized = LinkUtils.sanitizeLinkUrl(raw);
        if (sanitized == null) {
            return null;
        }
        try {
            URI uri = new URI(sanitized);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !"https".equals(scheme.toLowerCase(Locale.ROOT))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null) {
                return null;
            }
            return uri.toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
