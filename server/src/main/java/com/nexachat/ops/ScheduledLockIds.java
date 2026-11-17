package com.nexachat.ops;

/** Stable advisory lock ids for cluster-safe schedulers. */
public final class ScheduledLockIds {

    public static final long RAG_INDEX_BACKFILL = 2_530_010L;
    public static final long AVATAR_ORPHAN_SWEEP = 2_530_020L;
    public static final long RETENTION_SWEEP = 2_530_030L;
    public static final long DISAPPEARING_SWEEP = 2_530_040L;
    public static final long STREAK_REMINDER = 2_530_050L;

    private ScheduledLockIds() {
    }
}
