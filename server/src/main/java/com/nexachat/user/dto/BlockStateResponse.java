package com.nexachat.user.dto;

/**
 * Per-peer block probe for chat surfaces:
 * {@code GET /api/v1/users/{id}/block} → {@code {"blocked": true|false}}.
 */
public record BlockStateResponse(boolean blocked) {
}
