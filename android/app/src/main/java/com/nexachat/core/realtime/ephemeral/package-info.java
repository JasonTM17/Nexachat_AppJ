/**
 * Ephemeral presence and typing: {@code EphemeralStateRouter} picks the STOMP source or the
 * opt-in {@code FirebaseEphemeralStateSource} (RTDB, foreground only) behind
 * {@code EphemeralStateSource}, falling back to STOMP; also reads server-projected call status.
 */
package com.nexachat.core.realtime.ephemeral;
