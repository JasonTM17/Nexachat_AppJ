package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.List;

/** One search page, newest first. */
public record MessageSearchResult(
        List<ChatMessage> items, boolean hasMore, @Nullable Long nextBeforeSeq) {}
