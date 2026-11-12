package com.nexachat.notification;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class NotificationWorkSchedulerTest {
    @Test
    public void enqueueUsesDurableKeepPolicyAndNetworkConstraint() {
        WorkManager manager = mock(WorkManager.class);
        NotificationWorkRequest request = NotificationWorkRequest.message(
                "account-a", 4L, "conversation-1", "message-9", 9L);

        NotificationWorkScheduler.enqueue(manager, request);

        ArgumentCaptor<OneTimeWorkRequest> work = ArgumentCaptor.forClass(OneTimeWorkRequest.class);
        verify(manager).enqueueUniqueWork(
                eq("msg:account-a:4:message-9"), eq(ExistingWorkPolicy.KEEP), work.capture());
        assertEquals(NotificationWorkRequest.TYPE_MESSAGE,
                NotificationWorkRequest.fromData(work.getValue().getWorkSpec().input).getType());
        assertEquals(NetworkType.CONNECTED,
                work.getValue().getWorkSpec().constraints.getRequiredNetworkType());
    }
}
