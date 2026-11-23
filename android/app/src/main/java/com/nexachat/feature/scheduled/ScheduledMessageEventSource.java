package com.nexachat.feature.scheduled;

import androidx.annotation.NonNull;
import com.nexachat.core.network.stomp.ChatEvent;
import io.reactivex.rxjava3.core.Flowable;

/**
 * The scheduled list's view of the realtime stream: {@code SCHEDULED_MESSAGE_UPDATED}
 * inbox events only. The stream never carries authoritative state — a matching event
 * triggers a refetch.
 */
public interface ScheduledMessageEventSource {

    @NonNull
    Flowable<ChatEvent.ScheduledMessageUpdated> updates();
}
