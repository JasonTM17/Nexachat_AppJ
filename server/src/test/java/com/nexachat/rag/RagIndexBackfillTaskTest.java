package com.nexachat.rag;

import com.nexachat.ops.ScheduledAdvisoryLock;
import com.nexachat.ops.ScheduledLockIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The backfill must acquire, run and release its advisory lock through the
 * one-connection helper. Separate {@code tryAcquire}/{@code release} calls can
 * land on different pooled connections, leaving the session lock held by an
 * idle connection and silently stopping every later tick.
 */
class RagIndexBackfillTaskTest {

    private final RagEmbeddingService embeddings = mock(RagEmbeddingService.class);
    private final RagProperties properties = new RagProperties();
    private final ScheduledAdvisoryLock advisoryLock = mock(ScheduledAdvisoryLock.class);
    private final RagIndexBackfillTask task =
            new RagIndexBackfillTask(embeddings, properties, advisoryLock);

    @BeforeEach
    void enableBackfill() {
        properties.setEnabled(true);
        properties.getIndex().setBackfillEnabled(true);
        doAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1, Supplier.class);
            return action.get();
        }).when(advisoryLock).withLock(eq(ScheduledLockIds.RAG_INDEX_BACKFILL), any(Supplier.class));
    }

    @Test
    void backfillUsesTheSameConnectionLockHelperInsteadOfSplitAcquireRelease() {
        task.backfill();

        verify(advisoryLock).withLock(eq(ScheduledLockIds.RAG_INDEX_BACKFILL), any(Supplier.class));
        verify(advisoryLock, never()).tryAcquire(anyLong());
        verify(advisoryLock, never()).release(anyLong());
        verify(embeddings).backfill();
    }
}
