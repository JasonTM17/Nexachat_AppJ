package com.nexachat.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** Coordinates non-transactional storage files with the database transaction. */
public final class StorageTransactionCleanup {

    private static final Logger log = LoggerFactory.getLogger(StorageTransactionCleanup.class);

    private StorageTransactionCleanup() {
    }

    /**
     * Runs storage I/O with a caller transaction suspended. {@link #bind} runs
     * after this returns so that transaction can still delete the object on rollback.
     */
    public static <T> T outsideCallerTransaction(PlatformTransactionManager transactionManager,
                                                 Supplier<T> work) {
        TransactionTemplate suspended = new TransactionTemplate(transactionManager);
        suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return suspended.execute(status -> work.get());
    }

    /** Bind a newly stored object, including transaction-start and commit failures. */
    public static <T> T bind(StorageService storageService, String storageKey,
                             TransactionTemplate transaction, Supplier<T> bind) {
        boolean[] completionHandled = {false};
        try {
            return transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int outcome) {
                        // An afterCommit listener may throw after the row is durable.
                        // Never compensate a committed (or uncertain) reference.
                        completionHandled[0] = true;
                        if (outcome == STATUS_ROLLED_BACK) {
                            deleteSafely(storageService, storageKey, "rollback");
                        } else if (outcome != STATUS_COMMITTED) {
                            log.warn("Stored file {} retained after uncertain transaction outcome", storageKey);
                        }
                    }
                });
                T result = bind.get();
                if (status.isRollbackOnly()) {
                    throw new UnexpectedRollbackException("Stored object bind marked rollback-only");
                }
                return result;
            });
        } catch (RuntimeException | Error failure) {
            if (!completionHandled[0]) {
                deleteSafely(storageService, storageKey, "bind failure");
            }
            throw failure;
        }
    }

    static void deleteOnRollback(StorageService storageService, String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteSafely(storageService, storageKey, "rollback");
                }
            }
        });
    }

    static void deleteAfterCommit(StorageService storageService, String storageKey) {
        if (storageKey == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteSafely(storageService, storageKey, "commit");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteSafely(storageService, storageKey, "commit");
            }
        });
    }

    private static void deleteSafely(StorageService storageService, String storageKey, String outcome) {
        try {
            storageService.deleteIfExists(storageKey);
        } catch (RuntimeException | Error ex) {
            log.warn("Could not delete stored file {} after transaction {}", storageKey, outcome, ex);
        }
    }
}
