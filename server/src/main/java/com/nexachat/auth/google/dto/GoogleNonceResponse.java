package com.nexachat.auth.google.dto;

/**
 * @param nonce           single-use value to pass to Credential Manager
 * @param expiresInSeconds lifetime in seconds
 */
public record GoogleNonceResponse(String nonce, long expiresInSeconds) {
}
