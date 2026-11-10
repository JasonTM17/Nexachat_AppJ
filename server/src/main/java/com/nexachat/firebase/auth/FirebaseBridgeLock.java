package com.nexachat.firebase.auth;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Bounded in-process serialization used alongside the shared database user-row lock. */
public final class FirebaseBridgeLock {

    private static final int STRIPE_COUNT = 256;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];

    public FirebaseBridgeLock() {
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    public <T> T withUserLock(UUID userId, Supplier<T> operation) {
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    public void withUserLock(UUID userId, Runnable operation) {
        withUserLock(userId, () -> {
            operation.run();
            return null;
        });
    }

    ReentrantLock lockFor(UUID userId) {
        return stripes[Math.floorMod(userId.hashCode(), stripes.length)];
    }
}
