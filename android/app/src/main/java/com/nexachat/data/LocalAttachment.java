package com.nexachat.data;

import android.net.Uri;
import androidx.annotation.Nullable;

/** Metadata sniffed from a picked URI before upload. */
public record LocalAttachment(
        Uri uri,
        @Nullable String displayName,
        @Nullable String mimeType,
        long sizeBytes,
        String type) {}
