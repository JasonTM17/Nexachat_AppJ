package com.nexachat.notification;

import androidx.annotation.NonNull;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.data.SyncEngine;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Fetches the pointed event, rechecks access and publishes only a generic notification. */
@Singleton
public final class NotificationWorkExecutor {
    public enum Outcome { SUCCESS, RETRY, FAILURE }

    private final NotificationAdmission admission;
    private final SyncEngine syncEngine;
    private final MessageDao messageDao;
    private final ForegroundConversationTracker foreground;
    private final NotificationOccurrenceLedger occurrenceLedger;
    private final MessageNotifications messageNotifications;
    private final ReminderNotifications reminderNotifications;

    @Inject
    public NotificationWorkExecutor(
            @NonNull NotificationAdmission admission,
            @NonNull SyncEngine syncEngine,
            @NonNull MessageDao messageDao,
            @NonNull ForegroundConversationTracker foreground,
            @NonNull NotificationOccurrenceLedger occurrenceLedger,
            @NonNull MessageNotifications messageNotifications,
            @NonNull ReminderNotifications reminderNotifications) {
        this.admission = admission;
        this.syncEngine = syncEngine;
        this.messageDao = messageDao;
        this.foreground = foreground;
        this.occurrenceLedger = occurrenceLedger;
        this.messageNotifications = messageNotifications;
        this.reminderNotifications = reminderNotifications;
    }

    @NonNull
    public Single<Outcome> execute(@NonNull NotificationWorkRequest request) {
        if (!request.isValid()) return Single.just(Outcome.FAILURE);
        if (foreground.isForegroundConversation(request.getConversationId())) {
            return Single.just(Outcome.SUCCESS);
        }

        return admission.isCurrent(request.getRecipientId(), request.getSessionEpoch(),
                        request.getConversationId())
                .subscribeOn(Schedulers.io())
                .flatMap(current -> {
                    if (!current) return Single.just(Outcome.SUCCESS);
                    return syncEngine.sync()
                            .andThen(admission.isCurrent(request.getRecipientId(),
                                    request.getSessionEpoch(), request.getConversationId()))
                            .flatMap(stillCurrent -> {
                                if (!stillCurrent) return Single.just(Outcome.SUCCESS);
                                return hasFetchedEvent(request).flatMap(found -> found
                                        ? publish(request)
                                        : Single.just(Outcome.SUCCESS));
                            });
                })
                .onErrorReturn(ignored -> Outcome.RETRY);
    }

    private Single<Boolean> hasFetchedEvent(NotificationWorkRequest request) {
        if (!NotificationWorkRequest.TYPE_MESSAGE.equals(request.getType())) {
            // Reminder pushes are occurrence snapshots; the recurring row may
            // already have advanced by the time this durable worker runs.
            return Single.just(true);
        }
        return messageDao.byId(request.getMessageId())
                .map((MessageEntity message) -> request.getConversationId().equals(
                                message.getConversationId())
                        && request.getSequence() == message.getSeq())
                .defaultIfEmpty(false);
    }

    private Single<Outcome> publish(NotificationWorkRequest request) {
        return admission.isCurrent(request.getRecipientId(), request.getSessionEpoch(),
                        request.getConversationId())
                .map(current -> {
                    if (!current || foreground.isForegroundConversation(request.getConversationId())) {
                        return Outcome.SUCCESS;
                    }
                    boolean message = NotificationWorkRequest.TYPE_MESSAGE.equals(request.getType());
                    boolean enabled = message
                            ? messageNotifications.isEnabled()
                            : reminderNotifications.isEnabled();
                    if (!enabled) return Outcome.SUCCESS;

                    admission.runIfCurrent(request.getRecipientId(), request.getSessionEpoch(),
                            request.getConversationId(), ignoredConversation -> {
                                if (foreground.isForegroundConversation(
                                        request.getConversationId())) return;
                                occurrenceLedger.deliverOnce(request.occurrenceKey(), () -> message
                                        ? messageNotifications.show(request)
                                        : reminderNotifications.show(request));
                            });
                    return Outcome.SUCCESS;
                });
    }
}
