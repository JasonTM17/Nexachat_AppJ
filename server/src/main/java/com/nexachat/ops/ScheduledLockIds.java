package com.nexachat.ops;

/** Stable advisory lock ids for cluster-safe schedulers. */
public final class ScheduledLockIds {

    public static final long RAG_INDEX_BACKFILL = 2_530_010L;
    public static final long AVATAR_ORPHAN_SWEEP = 2_530_020L;
    public static final long RETENTION_SWEEP = 2_530_030L;

    private ScheduledLockIds() {
    }
}
