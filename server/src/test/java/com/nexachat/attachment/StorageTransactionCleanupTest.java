package com.nexachat.attachment;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageTransactionCleanupTest {
    private final StorageService storage = mock(StorageService.class);
    private final DataSource source = mock(DataSource.class, RETURNS_DEEP_STUBS);
    private final TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    private static final String KEY = "attachments/new.png";

    @Test
    void transactionStartFailureCompensatesAndKeepsOriginalError() throws Exception {
        when(source.getConnection()).thenThrow(new SQLException("db unavailable"));
        assertThatThrownBy(() -> StorageTransactionCleanup.bind(storage, KEY, transaction, () -> "unused"))
                .isInstanceOf(CannotCreateTransactionException.class).hasRootCauseMessage("db unavailable");
        verify(storage).deleteIfExists(KEY);
    }

    @Test
    void rollbackOnlyDoesNotReturnSuccess() {
        assertThatThrownBy(() -> StorageTransactionCleanup.bind(storage, KEY, transaction, () -> {
            new TransactionTemplate(transaction.getTransactionManager()).executeWithoutResult(status -> status.setRollbackOnly());
            return "not committed";
        })).isInstanceOf(UnexpectedRollbackException.class);
        verify(storage).deleteIfExists(KEY);
    }

    @Test
    void unknownCommitOutcomeRetainsObjectForReconciliation() throws Exception {
        Connection connection = source.getConnection();
        doThrow(new SQLException("commit failed")).when(connection).commit();
        assertThatThrownBy(() -> StorageTransactionCleanup.bind(storage, KEY, transaction, () -> "bound"))
                .hasRootCauseMessage("commit failed");
        // A commit exception can have an unknown outcome: keep the object rather
        // than deleting a potentially committed reference.
        verify(storage, never()).deleteIfExists(KEY);
    }

    @Test
    void afterCommitFailureNeverDeletesCommittedObject() {
        IllegalStateException original = new IllegalStateException("listener failed");
        assertThat(catchThrowable(() -> StorageTransactionCleanup.bind(storage, KEY, transaction, () -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { throw original; }
            });
            return "committed";
        }))).isSameAs(original);
        verify(storage, never()).deleteIfExists(KEY);
    }

    @Test
    void cleanupErrorDoesNotReplaceOriginalBindError() {
        AssertionError original = new AssertionError("bind failed");
        doThrow(new AssertionError("cleanup failed")).when(storage).deleteIfExists(KEY);
        assertThat(catchThrowable(() -> StorageTransactionCleanup.bind(storage, KEY, transaction, () -> {
            throw original;
        }))).isSameAs(original);
        verify(storage).deleteIfExists(KEY);
    }
}
