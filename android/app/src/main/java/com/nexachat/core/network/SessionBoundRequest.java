package com.nexachat.core.network;

/** Local Retrofit tag for deferred work authorized by a specific login session. */
public record SessionBoundRequest(long epoch) {}
