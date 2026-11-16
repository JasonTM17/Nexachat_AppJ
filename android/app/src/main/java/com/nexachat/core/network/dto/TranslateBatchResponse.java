package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.List;

/** Reflective Moshi bean for the translate-batch response envelope. */
public final class TranslateBatchResponse {

    /** Per-slot batch result: {@code error} is a stable API code or null. */
    public static final class Item {
        private String messageId;
        private String text;
        private String sourceLang;
        private String targetLang;
        private Boolean cached;
        private String model;
        private String error;

        @Nullable public String getMessageId() { return messageId; }
        @Nullable public String getText() { return text; }
        @Nullable public String getSourceLang() { return sourceLang; }
        @Nullable public String getTargetLang() { return targetLang; }
        public boolean isCached() { return cached != null && cached; }
        @Nullable public String getModel() { return model; }
        @Nullable public String getError() { return error; }
    }

    private List<Item> results;

    @Nullable public List<Item> getResults() {
        return results;
    }
}
