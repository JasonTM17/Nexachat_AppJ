/**
 * Optional Firebase bridge, absent unless {@code nexachat.firebase.realtime-enabled}
 * and a database URL are set: Firebase Auth token/lease endpoints ({@code auth}),
 * server-only RTDB REST client ({@code client}), post-commit authorization mirrors and
 * lease sweeps ({@code projection}), and display-only call status ({@code call}).
 * PostgreSQL and the Nexa JWT stay authoritative.
 */
package com.nexachat.firebase;
