package com.nexachat.label;

/** Optional fields for updating a caller-owned label. */
public record PatchLabelRequest(String name, String colorId) {
}
