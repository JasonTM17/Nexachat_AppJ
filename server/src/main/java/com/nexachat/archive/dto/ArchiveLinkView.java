package com.nexachat.archive.dto;

/**
 * The extracted URL of a link item. The server never fetches it and exposes no
 * preview metadata for it.
 */
public record ArchiveLinkView(String url) {
}
