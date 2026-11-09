package com.nexachat.feature.reminder;

import androidx.annotation.NonNull;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * In-process delivery of the data-only {@code REMINDER} push. A visible reminder
 * board refreshes when its conversation arrives; the push itself never carries
 * authoritative state.
 */
@Singleton
public final class ReminderPushEvents {

    private final Subject<String> conversationIds =
            PublishSubject.<String>create().toSerialized();

    @Inject
    public ReminderPushEvents() { }

    public void onReminderPush(@NonNull String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return;
        }
        conversationIds.onNext(conversationId.trim());
    }

    @NonNull
    public Observable<String> conversationIds() {
        return conversationIds.hide();
    }
}
