package com.nexachat.message.dto;

/** Authorized, lock-free message-feed upper bound and generation snapshot. */
public record SyncFence(long changeUpperBound, long generation) {
}
