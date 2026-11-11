package com.nexachat.ws;

import com.nexachat.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Orders same-process STOMP work against access-token revocation commits.
 *
 * <p>Read leases cover inbound handler execution and the final outbound socket
 * write. A revocation takes the matching stripe's write lock before changing
 * account state and keeps it until transaction completion. Stripes bound the
 * lock table independently of the number of accounts.
 */
@Component
public class StompRevocationFence {

    private static final int STRIPE_COUNT = 256;

    private final UserRepository userRepository;
    private final ReadWriteLock[] stripes = new ReadWriteLock[STRIPE_COUNT];
    private final ReentrantLock[] sessionWriteStripes = new ReentrantLock[STRIPE_COUNT];

    public StompRevocationFence(UserRepository userRepository) {
        this.userRepository = userRepository;
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new ReentrantReadWriteLock(true);
            sessionWriteStripes[index] = new ReentrantLock(true);
        }
    }

    /** Acquires a fair read lease that must be closed on the acquiring thread. */
    public ReadLease acquireRead(UUID userId) {
        Lock lock = stripe(userId).readLock();
        lock.lock();
        return new ReadLease(lock);
    }

    /**
     * Serializes writes to one local WebSocket session while its decorated
     * send handler runs. Stripes keep lock storage bounded as sessions churn.
     */
    public SessionWriteLease acquireSessionWrite(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        ReentrantLock lock = sessionWriteStripes[Math.floorMod(sessionId.hashCode(), STRIPE_COUNT)];
        lock.lock();
        return new SessionWriteLease(lock);
    }

    /**
     * Acquires the write side before a revocation mutates rows and releases it
     * only after the current transaction's commit/rollback callbacks complete.
     */
    public void holdWriteUntilTransactionCompletion(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("STOMP revocation fence requires an active transaction");
        }
        Lock lock = stripe(userId).writeLock();
        lock.lock();
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    lock.unlock();
                }
            });
        } catch (RuntimeException failure) {
            lock.unlock();
            throw failure;
        }
    }

    /** Reads the authoritative cutoff while the caller holds this user's read lease. */
    public boolean isCurrent(UUID userId, Instant issuedAt, Instant expiresAt) {
        if (userId == null || issuedAt == null || expiresAt == null
                || !Instant.now().isBefore(expiresAt)) {
            return false;
        }
        return userRepository.findAuthValidityById(userId)
                .map(validity -> validity.tokensValidAfter() == null
                        || issuedAt.isAfter(validity.tokensValidAfter()))
                .orElse(false);
    }

    private ReadWriteLock stripe(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        return stripes[Math.floorMod(userId.hashCode(), stripes.length)];
    }

    public static final class ReadLease implements AutoCloseable {
        private final Lock lock;
        private boolean closed;

        private ReadLease(Lock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                lock.unlock();
            }
        }
    }

    public static final class SessionWriteLease implements AutoCloseable {
        private final ReentrantLock lock;
        private boolean closed;

        private SessionWriteLease(ReentrantLock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                lock.unlock();
            }
        }
    }
}
