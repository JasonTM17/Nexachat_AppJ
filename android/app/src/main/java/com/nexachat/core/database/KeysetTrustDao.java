package com.nexachat.core.database;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

/**
 * Synchronous trust-record access; callers run them on a background scheduler.
 * The compare-and-flip that detects a roster change reads and writes inside
 * one {@link Transaction} so a concurrent verify or check can never interleave
 * between the read and the write.
 */
@Dao
public abstract class KeysetTrustDao {
    public static final String STATE_VERIFIED = "VERIFIED";
    public static final String STATE_CHANGED = "CHANGED";

    @Query("SELECT * FROM e2ee_keyset_trust WHERE owner_user_id = :ownerUserId"
            + " AND conversation_id = :conversationId LIMIT 1")
    @Nullable
    public abstract KeysetTrustEntity find(String ownerUserId, String conversationId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void put(KeysetTrustEntity row);

    @Query("DELETE FROM e2ee_keyset_trust WHERE owner_user_id = :ownerUserId"
            + " AND conversation_id = :conversationId")
    public abstract void delete(String ownerUserId, String conversationId);

    @Query("DELETE FROM e2ee_keyset_trust WHERE owner_user_id = :ownerUserId")
    public abstract void deleteForOwner(String ownerUserId);

    /**
     * Loads the row and flips VERIFIED to CHANGED when its stored digest
     * differs from {@code observedDigestHex}, recording that digest. Every
     * other state — a missing row, an existing CHANGED row, or a match — is
     * returned untouched so CHANGED stays sticky until a new verification.
     */
    @Transaction
    @Nullable
    public KeysetTrustEntity checkAndMarkChanged(String ownerUserId,
            String conversationId, String observedDigestHex, long nowMs) {
        KeysetTrustEntity row = find(ownerUserId, conversationId);
        if (row != null && STATE_VERIFIED.equals(row.state)
                && !row.verifiedDigestHex.equals(observedDigestHex)) {
            row = new KeysetTrustEntity(ownerUserId, conversationId, STATE_CHANGED,
                    row.verifiedDigestHex, row.verifiedTranscript, observedDigestHex, nowMs);
            put(row);
        }
        return row;
    }

    /**
     * Loads the row and flips VERIFIED to CHANGED when the roster about to be
     * used can no longer form a direct transcript — a verified shape that
     * lost its two-user form is itself a keyset change. There is nothing to
     * digest, so {@code changed_digest_hex} stays null. A missing row or a
     * row already CHANGED is returned untouched, so CHANGED stays sticky
     * until a new verification.
     */
    @Transaction
    @Nullable
    public KeysetTrustEntity checkAndMarkReshaped(String ownerUserId,
            String conversationId, long nowMs) {
        KeysetTrustEntity row = find(ownerUserId, conversationId);
        if (row != null && STATE_VERIFIED.equals(row.state)) {
            row = new KeysetTrustEntity(ownerUserId, conversationId, STATE_CHANGED,
                    row.verifiedDigestHex, row.verifiedTranscript, null, nowMs);
            put(row);
        }
        return row;
    }
}
