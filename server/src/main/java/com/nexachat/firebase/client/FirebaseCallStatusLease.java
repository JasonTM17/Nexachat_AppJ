package com.nexachat.firebase.client;

/** Lease limits shared by the Firebase call-status writer and its patch validator. */
public final class FirebaseCallStatusLease {

    public static final long RINGING_MILLIS = 45_000L;
    public static final long ACTIVE_MILLIS = 120_000L;
    public static final long ENDED_MILLIS = 60_000L;

    private FirebaseCallStatusLease() {
    }
}
