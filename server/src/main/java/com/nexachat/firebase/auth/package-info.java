/**
 * Firebase bridge sessions: {@code FirebaseRealtimeController} issues custom tokens,
 * renews leases and closes bridge sessions after {@code FirebaseRealtimeAuthService}
 * revalidates the Nexa JWT state; {@code FirebaseSessionRevocationListener} cleans up
 * remotely after an access-token cutoff. Absent unless the Firebase bridge is enabled.
 */
package com.nexachat.firebase.auth;
