package com.nexachat.user.dto;

/**
 * DELETE /users/me body. An account with a password hash confirms with
 * {@code password}. An account without one confirms with {@code code}.
 */
public record DeleteAccountRequest(String password, String code) {
}
