package com.nexachat.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.data.SyncEngine;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public final class NotificationWorkExecutorTest {
    private static final String ACCOUNT = "account-a";
    private static final String CONVERSATION = "conversation-1";
    private static final String MESSAGE = "message-9";
    private static final long EPOCH = 4L;

    private final NotificationAdmission admission = mock(NotificationAdmission.class);
    private final SyncEngine syncEngine = mock(SyncEngine.class);
    private final MessageDao messageDao = mock(MessageDao.class);
    private final ForegroundConversationTracker foreground = new ForegroundConversationTracker();
    private final FakeOccurrenceLedger occurrenceLedger = new FakeOccurrenceLedger();
    private final MessageNotifications messageNotifications = mock(MessageNotifications.class);
    private final ReminderNotifications reminderNotifications = mock(ReminderNotifications.class);
    private NotificationWorkExecutor executor;
    private MessageEntity messageEntity;

    @Before
    public void setUp() {
        when(admission.isCurrent(ACCOUNT, EPOCH, CONVERSATION)).thenReturn(Single.just(true));
        when(admission.runIfCurrent(eq(ACCOUNT), eq(EPOCH), eq(CONVERSATION), any(java.util.function.Consumer.class)))
                .thenAnswer(invocation -> {
                    ((java.util.function.Consumer<com.nexachat.core.database.ConversationEntity>)
                            invocation.getArgument(3)).accept(
                                    mock(com.nexachat.core.database.ConversationEntity.class));
                    return true;
                });
        when(syncEngine.sync()).thenReturn(Completable.complete());
        messageEntity = mock(MessageEntity.class);
        when(messageEntity.getConversationId()).thenReturn(CONVERSATION);
        when(messageEntity.getSeq()).thenReturn(9L);
        when(messageDao.byId(MESSAGE)).thenReturn(Maybe.just(messageEntity));
        when(messageNotifications.isEnabled()).thenReturn(true);
        when(messageNotifications.show(any())).thenReturn(true);
        when(reminderNotifications.isEnabled()).thenReturn(true);
        when(reminderNotifications.show(any())).thenReturn(true);
        executor = newExecutor();
    }

    @Test
    public void duplicateDeliveryProducesOneNotification() {
        NotificationWorkRequest request = messageRequest();

        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS, executor.execute(request).blockingGet());
        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS, executor.execute(request).blockingGet());

        verify(messageNotifications, times(1)).show(request);
    }

    @Test
    public void failedFetchRetriesThenPublishesAfterRecovery() {
        when(syncEngine.sync()).thenReturn(
                Completable.error(new IOException("offline")), Completable.complete());

        assertEquals(NotificationWorkExecutor.Outcome.RETRY,
                executor.execute(messageRequest()).blockingGet());
        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS,
                executor.execute(messageRequest()).blockingGet());

        verify(messageNotifications, times(1)).show(any());
    }

    @Test
    public void recreatedWorkerKeepsTheCompletedOccurrenceDeduplicated() {
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        NotificationWorkRequest request = messageRequest();

        ListenableWorker.Result first = worker(context, request, executor).createWork().blockingGet();
        ListenableWorker.Result recreated = worker(context, request, newExecutor()).createWork().blockingGet();

        assertEquals(ListenableWorker.Result.success(), first);
        assertEquals(ListenableWorker.Result.success(), recreated);
        verify(messageNotifications, times(1)).show(any());
    }

    @Test
    public void permissionDeniedDoesNotPostOrMarkAnAlert() {
        when(messageNotifications.isEnabled()).thenReturn(false);
        when(reminderNotifications.isEnabled()).thenReturn(false);

        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS,
                executor.execute(messageRequest()).blockingGet());
        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS,
                executor.execute(reminderRequest("0123456789abcdef0123456789abcdef")).blockingGet());

        verify(messageNotifications, never()).show(any());
        verify(reminderNotifications, never()).show(any());
        assertTrue(occurrenceLedger.seen.isEmpty());
    }

    @Test
    public void removedMembershipBetweenPushAndFetchSuppressesTheAlert() {
        when(admission.isCurrent(ACCOUNT, EPOCH, CONVERSATION))
                .thenReturn(Single.just(true), Single.just(false));

        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS,
                executor.execute(messageRequest()).blockingGet());

        verify(syncEngine).sync();
        verify(messageDao, never()).byId(MESSAGE);
        verify(messageNotifications, never()).show(any());
    }

    @Test
    public void finalMembershipFenceSuppressesAlertWhenAccessWasRevokedDuringPreflight() {
        when(admission.runIfCurrent(
                eq(ACCOUNT), eq(EPOCH), eq(CONVERSATION), any(java.util.function.Consumer.class)))
                .thenReturn(false);

        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS,
                executor.execute(messageRequest()).blockingGet());

        verify(messageNotifications).isEnabled();
        verify(messageNotifications, never()).show(any());
    }

    @Test
    public void accountSwitchToBRejectsTheQueuedAWork() {
        when(admission.isCurrent(ACCOUNT, EPOCH, CONVERSATION)).thenReturn(Single.just(false));

        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS,
                executor.execute(messageRequest()).blockingGet());

        verify(syncEngine, never()).sync();
        verify(messageNotifications, never()).show(any());
    }

    @Test
    public void logoutThenReloginToARejectsThePriorEpochWork() {
        // The user id is unchanged; only the persisted session epoch changed.
        when(admission.isCurrent(ACCOUNT, EPOCH, CONVERSATION)).thenReturn(Single.just(false));

        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS,
                executor.execute(messageRequest()).blockingGet());

        verify(messageNotifications, never()).show(any());
    }

    @Test
    public void rescheduledReminderOccurrenceNotifiesAgain() {
        NotificationWorkRequest first = reminderRequest("0123456789abcdef0123456789abcdef");
        NotificationWorkRequest rescheduled = reminderRequest("fedcba9876543210fedcba9876543210");

        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS, executor.execute(first).blockingGet());
        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS,
                executor.execute(rescheduled).blockingGet());

        verify(reminderNotifications, times(2)).show(any());
        assertEquals(2, occurrenceLedger.seen.size());
    }

    @Test
    public void foregroundConversationDoesNotTriggerFetchOrAlert() {
        foreground.onConversationResumed(CONVERSATION);

        assertEquals(NotificationWorkExecutor.Outcome.SUCCESS,
                executor.execute(messageRequest()).blockingGet());

        verify(syncEngine, never()).sync();
        verify(messageNotifications, never()).show(any());
    }

    private NotificationWorkExecutor newExecutor() {
        return new NotificationWorkExecutor(admission, syncEngine, messageDao, foreground,
                occurrenceLedger, messageNotifications, reminderNotifications);
    }

    private static MessageNotificationWorker worker(
            Context context, NotificationWorkRequest request, NotificationWorkExecutor executor) {
        WorkerParameters parameters = mock(WorkerParameters.class);
        when(parameters.getInputData()).thenReturn(request.toData());
        return new MessageNotificationWorker(context, parameters, executor);
    }

    private static NotificationWorkRequest messageRequest() {
        return NotificationWorkRequest.message(ACCOUNT, EPOCH, CONVERSATION, MESSAGE, 9L);
    }

    private static NotificationWorkRequest reminderRequest(String occurrence) {
        return NotificationWorkRequest.reminder(
                ACCOUNT, EPOCH, CONVERSATION, "reminder-2", occurrence);
    }

    private static final class FakeOccurrenceLedger implements NotificationOccurrenceLedger {
        private final Set<String> seen = new HashSet<>();

        @Override
        public synchronized boolean deliverOnce(String key, java.util.function.BooleanSupplier publish) {
            if (seen.contains(key)) return true;
            if (!publish.getAsBoolean()) return false;
            seen.add(key);
            return true;
        }
    }
}
