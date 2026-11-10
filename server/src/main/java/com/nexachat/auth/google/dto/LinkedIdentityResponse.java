package com.nexachat.auth.google.dto;

import java.time.Instant;

/**
 * A linked external identity as shown to its owner.
 *
 * @param provider  stable provider key ({@code google})
 * @param email     email Google reported when the account was linked
 * @param linkedAt  when the link was created
 */
public record LinkedIdentityResponse(String provider, String email, Instant linkedAt) {
}
