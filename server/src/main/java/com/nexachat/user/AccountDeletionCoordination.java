package com.nexachat.user;

import java.util.UUID;

/** Optional hook for integration tests (e.g. pause before tombstone under row lock). */
public interface AccountDeletionCoordination {

    void beforeTombstone(UUID userId);
}
