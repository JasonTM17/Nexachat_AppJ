package com.nexachat.firebase.auth;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Bounded in-process serialization used alongside the shared database user-row
 * lock. Multi-user callers must use {@link #withUserLocks(Collection, Runnable)}
 * so every path acquires distinct stripes in the same ascending order.
 */
public final class FirebaseBridgeLock {

    private static final int STRIPE_COUNT = 256;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];
    private final IntConsumer acquisitionListener;

    public FirebaseBridgeLock() {
        this(stripe -> { });
    }

    /** Package-private seam for deterministic lock-order tests. */
    FirebaseBridgeLock(IntConsumer acquisitionListener) {
        this.acquisitionListener = Objects.requireNonNull(acquisitionListener);
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    public <T> T withUserLock(UUID userId, Supplier<T> operation) {
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        try {
            acquisitionListener.accept(stripeIndex(userId));
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

    /**
     * Acquires every distinct stripe for these users in ascending stripe order.
     * Callers with several users must use this instead of nesting
     * {@link #withUserLock(UUID, Runnable)} in user-id order.
     */
    public void withUserLocks(Collection<UUID> userIds, Runnable operation) {
        int[] indexes = userIds.stream()
                .filter(Objects::nonNull)
                .mapToInt(FirebaseBridgeLock::stripeIndex)
                .distinct()
                .sorted()
                .toArray();
        withStripeLockAt(indexes, 0, operation);
    }

    private void withStripeLockAt(int[] indexes, int position, Runnable operation) {
        if (position == indexes.length) {
            operation.run();
            return;
        }
        ReentrantLock lock = stripes[indexes[position]];
        lock.lock();
        try {
            acquisitionListener.accept(indexes[position]);
            withStripeLockAt(indexes, position + 1, operation);
        } finally {
            lock.unlock();
        }
    }

    ReentrantLock lockFor(UUID userId) {
        return stripes[stripeIndex(userId)];
    }

    static int stripeIndex(UUID userId) {
        return Math.floorMod(userId.hashCode(), STRIPE_COUNT);
    }
}
