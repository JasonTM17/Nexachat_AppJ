package com.nexachat.archive;

/**
 * Per-item kind on the archive wire: images and videos on the media tab,
 * documents on the file tab, extracted URLs on the link tab.
 */
public enum ArchiveItemKind {
    IMAGE,
    VIDEO,
    FILE,
    LINK
}
