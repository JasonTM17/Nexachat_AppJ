package com.nexachat.data;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.HashMap;
import java.util.Map;

/** Serializes acceptance into the durable outbox within each conversation. */
final class OutboxWriteQueue {
    private final Map<String, Completable> tails = new HashMap<>();

    Completable enqueue(String conversation, Completable write) {
        return Completable.defer(() -> {
            CompletableSubject finished = CompletableSubject.create();
            CompletableSubject result = CompletableSubject.create();
            Completable prior;
            synchronized (tails) {
                prior = tails.getOrDefault(conversation, Completable.complete());
                tails.put(conversation, finished);
            }
            // The view observes the result. Its disposal does not cancel a submitted write.
            prior.andThen(write).doFinally(() -> {
                finished.onComplete();
                synchronized (tails) {
                    tails.remove(conversation, finished);
                }
            }).subscribe(result::onComplete, result::onError);
            return result.hide();
        });
    }
}
