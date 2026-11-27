package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** Coordinates the composer's cancel control with durable acceptance and worker dispatch. */
public final class AttachmentSendTicket {
    private final String id = UUID.randomUUID().toString();
    @Nullable private String owner;
    private boolean cancelled;
    private boolean claimed;

    public String id() {
        return id;
    }

    synchronized void accept(String ownerUserId, Runnable persist) {
        if (cancelled) throw new CancellationException("Attachment selection cancelled");
        persist.run();
        owner = ownerUserId;
    }

    synchronized boolean claim() {
        if (cancelled) return false;
        claimed = true;
        return true;
    }

    synchronized boolean cancel() {
        if (claimed) return false;
        cancelled = true;
        return true;
    }

    synchronized boolean isCancelled() {
        return cancelled;
    }

    @Nullable synchronized String owner() {
        return owner;
    }
}
