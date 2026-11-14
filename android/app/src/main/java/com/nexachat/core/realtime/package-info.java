/**
 * Realtime helpers. {@code ephemeral} routes presence and typing through STOMP or an
 * opt-in Firebase RTDB source ({@code EphemeralStateRouter}) while the durable STOMP
 * stream stays active, and reads the server-projected call status.
 */
package com.nexachat.core.realtime;
