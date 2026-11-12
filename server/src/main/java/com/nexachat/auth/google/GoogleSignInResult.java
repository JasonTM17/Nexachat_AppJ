package com.nexachat.auth.google;

import com.nexachat.auth.dto.AuthResponse;

/** Outcome of {@code POST /auth/google}: existing session ({@code 200}) or new account ({@code 201}). */
public record GoogleSignInResult(AuthResponse response, boolean created) {
}
