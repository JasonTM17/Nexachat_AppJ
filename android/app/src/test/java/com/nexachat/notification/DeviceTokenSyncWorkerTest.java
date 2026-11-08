package com.nexachat.notification;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import org.junit.Test;

public final class DeviceTokenSyncWorkerTest {

    @Test
    public void missingExecutorRetriesWithinBudgetInsteadOfSucceeding() {
        DeviceTokenSyncWorker worker = new DeviceTokenSyncWorker(
                mock(Context.class), parameters(0), null);

        assertEquals(ListenableWorker.Result.retry(), worker.createWork().blockingGet());
    }

    @Test
    public void missingExecutorFailsAfterRetryBudget() {
        DeviceTokenSyncWorker worker = new DeviceTokenSyncWorker(
                mock(Context.class), parameters(DeviceTokenRetryPolicy.MAX_RETRIES), null);

        assertEquals(ListenableWorker.Result.failure(), worker.createWork().blockingGet());
    }

    private static WorkerParameters parameters(int runAttemptCount) {
        WorkerParameters parameters = mock(WorkerParameters.class);
        when(parameters.getRunAttemptCount()).thenReturn(runAttemptCount);
        return parameters;
    }
}
