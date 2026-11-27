package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.Test;

public final class OutboxAcceptanceTest {
    @Test
    public void closingTheViewDoesNotLoseSubmittedWritesOrChangeTheirOrder() {
        var queue = new OutboxWriteQueue();
        var fileReady = CompletableSubject.create();
        List<String> accepted = new ArrayList<>();
        queue.enqueue("group-a", fileReady.doOnComplete(() -> accepted.add("file"))).test();
        var observer = queue.enqueue("group-a", Completable.fromAction(
                () -> accepted.add("accepted-after-close"))).test();
        queue.enqueue("group-a", Completable.fromAction(() -> accepted.add("text"))).test();
        queue.enqueue("group-b", Completable.fromAction(() -> accepted.add("other"))).test();

        observer.dispose();
        assertEquals(List.of("other"), accepted);
        fileReady.onComplete();
        assertEquals(List.of("other", "file", "accepted-after-close", "text"), accepted);
    }

    @Test
    public void cancellationDuringPickerReadPreventsPublishingAndClaimingTheRow() {
        var ticket = new AttachmentSendTicket();
        assertTrue(ticket.cancel());
        List<String> accepted = new ArrayList<>();

        assertThrows(CancellationException.class, () ->
                ticket.accept("owner", () -> accepted.add(ticket.id())));
        assertTrue(accepted.isEmpty());
        assertNull(ticket.owner());
        assertFalse(ticket.claim());
    }

    @Test
    public void onlyACancelBeforeWorkerClaimCanRemoveAnAcceptedSelection() {
        var cancelled = new AttachmentSendTicket();
        cancelled.accept("owner", () -> {});
        assertTrue(cancelled.cancel());
        assertEquals("owner", cancelled.owner());
        assertFalse(cancelled.claim());

        var claimed = new AttachmentSendTicket();
        claimed.accept("owner", () -> {});
        assertTrue(claimed.claim());
        assertFalse(claimed.cancel());
    }
}
