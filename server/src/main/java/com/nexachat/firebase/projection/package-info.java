/**
 * Authorization mirrors in RTDB derived from PostgreSQL after commit
 * ({@code FirebaseAuthorizationProjectionService} and its listener), with bounded retries,
 * startup backfill, expired-lease sweeps and low-cardinality metrics. Present only when the
 * Firebase bridge is enabled.
 */
package com.nexachat.firebase.projection;
