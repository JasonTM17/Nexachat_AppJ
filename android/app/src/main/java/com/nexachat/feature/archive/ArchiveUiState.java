package com.nexachat.feature.archive;

import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable render state for the three archive tabs and their independent cursors. */
public final class ArchiveUiState {

    private final ArchiveType selectedType;
    private final List<ArchiveSender> senders;
    private final boolean sendersLoaded;
    @Nullable private final String senderId;
    @Nullable private final String from;
    @Nullable private final String to;
    private final Map<ArchiveType, ArchivePageState> pages;

    public ArchiveUiState() {
        this(ArchiveType.MEDIA, Collections.emptyList(), false, null, null, null,
                emptyPages());
    }

    public ArchiveUiState(
            ArchiveType selectedType,
            List<ArchiveSender> senders,
            boolean sendersLoaded,
            @Nullable String senderId,
            @Nullable String from,
            @Nullable String to,
            Map<ArchiveType, ArchivePageState> pages) {
        this.selectedType = Objects.requireNonNull(selectedType, "selectedType");
        this.senders = List.copyOf(senders);
        this.sendersLoaded = sendersLoaded;
        this.senderId = senderId;
        this.from = from;
        this.to = to;
        EnumMap<ArchiveType, ArchivePageState> copy = new EnumMap<>(ArchiveType.class);
        copy.putAll(pages);
        for (ArchiveType type : ArchiveType.values()) {
            copy.putIfAbsent(type, new ArchivePageState());
        }
        this.pages = Collections.unmodifiableMap(copy);
    }

    private static Map<ArchiveType, ArchivePageState> emptyPages() {
        EnumMap<ArchiveType, ArchivePageState> pages = new EnumMap<>(ArchiveType.class);
        for (ArchiveType type : ArchiveType.values()) {
            pages.put(type, new ArchivePageState());
        }
        return pages;
    }

    public ArchiveType getSelectedType() { return selectedType; }
    public List<ArchiveSender> getSenders() { return senders; }
    public boolean areSendersLoaded() { return sendersLoaded; }
    @Nullable public String getSenderId() { return senderId; }
    @Nullable public String getFrom() { return from; }
    @Nullable public String getTo() { return to; }
    public ArchivePageState getPage(ArchiveType type) { return pages.get(type); }
    public Map<ArchiveType, ArchivePageState> getPages() { return pages; }

    public boolean hasFilters() {
        return senderId != null || from != null || to != null;
    }
}
