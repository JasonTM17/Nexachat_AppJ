package com.nexachat.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Coordinates non-transactional storage files with the database transaction. */
final class StorageTransactionCleanup {

    private static final Logger log = LoggerFactory.getLogger(StorageTransactionCleanup.class);

    private StorageTransactionCleanup() {
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
        } catch (RuntimeException ex) {
            log.warn("Could not delete stored file {} after transaction {}", storageKey, outcome, ex);
        }
    }
}
