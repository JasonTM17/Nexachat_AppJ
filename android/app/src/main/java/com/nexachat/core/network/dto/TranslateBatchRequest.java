package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.List;

/** Reflective Moshi bean for {@code POST /conversations/{id}/translate-batch}. */
public final class TranslateBatchRequest {
    private final List<String> messageIds;
    private final String targetLang;

    public TranslateBatchRequest() {
        this(null, null);
    }

    public TranslateBatchRequest(List<String> messageIds, String targetLang) {
        this.messageIds = messageIds;
        this.targetLang = targetLang;
    }

    @NonNull public List<String> getMessageIds() {
        return messageIds;
    }

    @NonNull public String getTargetLang() {
        return targetLang;
    }
}
