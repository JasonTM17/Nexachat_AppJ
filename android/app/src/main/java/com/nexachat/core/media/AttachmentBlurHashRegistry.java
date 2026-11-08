package com.nexachat.core.media;

import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.AttachmentDto;

/** Bounded process-local bridge for BlurHash values omitted from Room attachment JSON. */
public final class AttachmentBlurHashRegistry {

    public static final int MAX_ENTRIES = 512;

    private static final BoundedLruCache<String, String> HASHES =
            new BoundedLruCache<>(MAX_ENTRIES);

    private AttachmentBlurHashRegistry() { }

    public static void remember(@Nullable AttachmentDto attachment) {
        if (attachment == null) {
            return;
        }
        remember(attachment.getId(), attachment.getBlurHash());
    }

    public static void remember(@Nullable String attachmentId, @Nullable String blurHash) {
        if (attachmentId == null
                || MediaText.isBlank(attachmentId)
                || blurHash == null
                || blurHash.length() < 6
                || blurHash.length() > 64
                || !BlurHashDecoder.isValid(blurHash)) {
            return;
        }
        HASHES.put(attachmentId, blurHash);
    }

    @Nullable
    public static String get(@Nullable String attachmentId) {
        return attachmentId == null ? null : HASHES.get(attachmentId);
    }

    public static int size() {
        return HASHES.size();
    }

}
