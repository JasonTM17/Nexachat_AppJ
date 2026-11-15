package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

/**
 * Durable trust record for one direct conversation's device keyset, keyed by
 * the owning account so rows never leak across sign-in boundaries.
 *
 * <p>A missing row means UNVERIFIED (trust on first use). A VERIFIED row pins
 * the digest of the transcript the user compared out of band; a CHANGED row
 * records that the roster observed afterwards no longer matches it. On a
 * CHANGED row, {@code changed_digest_hex} holds the observed digest that
 * differed, or stays null when the detected change was a roster reshape
 * that produced no comparable transcript. Only a fresh verification clears
 * CHANGED.
 */
@Entity(tableName = "e2ee_keyset_trust",
        primaryKeys = {"owner_user_id", "conversation_id"})
public final class KeysetTrustEntity {
    @NonNull @ColumnInfo(name = "owner_user_id") public final String ownerUserId;
    @NonNull @ColumnInfo(name = "conversation_id") public final String conversationId;
    @NonNull @ColumnInfo(name = "state") public final String state;
    @NonNull @ColumnInfo(name = "verified_digest_hex") public final String verifiedDigestHex;
    @NonNull @ColumnInfo(name = "verified_transcript") public final byte[] verifiedTranscript;
    @Nullable @ColumnInfo(name = "changed_digest_hex") public final String changedDigestHex;
    @ColumnInfo(name = "updated_at_ms") public final long updatedAtMs;

    public KeysetTrustEntity(@NonNull String ownerUserId, @NonNull String conversationId,
            @NonNull String state, @NonNull String verifiedDigestHex,
            @NonNull byte[] verifiedTranscript, @Nullable String changedDigestHex,
            long updatedAtMs) {
        this.ownerUserId = ownerUserId;
        this.conversationId = conversationId;
        this.state = state;
        this.verifiedDigestHex = verifiedDigestHex;
        this.verifiedTranscript = verifiedTranscript;
        this.changedDigestHex = changedDigestHex;
        this.updatedAtMs = updatedAtMs;
    }
}
