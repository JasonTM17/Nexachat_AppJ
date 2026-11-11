package com.nexachat.core.realtime.ephemeral;

import com.nexachat.core.network.TokenRefresher;
import io.reactivex.rxjava3.core.Completable;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;

/** Serializes Firebase Auth mutations and atomically admits session-bound operations. */
final class FirebaseAuthQueue {
    private static final String APP_NAME_PREFIX = "NexaChatEphemeral";
    private Completable queue = Completable.complete();

    static String runtimeAppName(long epoch, long generation) {
        return APP_NAME_PREFIX + "_" + epoch + "_" + generation;
    }

    Completable enqueue(Completable operation) {
        synchronized (this) {
            queue = queue.onErrorComplete().andThen(operation).cache();
            return queue;
        }
    }

    Completable enqueueIfSessionCurrent(
            TokenRefresher tokenRefresher, long expectedEpoch, Completable operation) {
        AtomicReference<Completable> queued = new AtomicReference<>();
        boolean admitted = tokenRefresher.runIfSessionCurrent(
                expectedEpoch, () -> queued.set(enqueue(operation)));
        if (!admitted) {
            return Completable.error(new IllegalStateException("stale Firebase session"));
        }
        return Objects.requireNonNull(queued.get());
    }

    Completable enqueueSessionOperation(
            TokenRefresher tokenRefresher,
            long expectedEpoch,
            BooleanSupplier isCurrent,
            Completable operation,
            Supplier<Completable> staleCleanup) {
        Completable fencedOperation = operation.andThen(Completable.defer(() -> {
            if (isCurrent.getAsBoolean()) return Completable.complete();
            return staleCleanup.get().andThen(Completable.error(
                    new IllegalStateException("stale Firebase session")));
        }));
        return enqueueIfSessionCurrent(tokenRefresher, expectedEpoch, fencedOperation);
    }
}
