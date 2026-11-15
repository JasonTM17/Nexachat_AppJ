package com.nexachat.core.crypto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.database.KeysetTrustDao;
import com.nexachat.core.database.KeysetTrustEntity;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Durable per-conversation verdict over the direct-chat device keyset.
 *
 * <p>A missing row means UNVERIFIED: first contact is trust-on-first-use and
 * must be labelled, never silently trusted. A VERIFIED row pins the SHA-256
 * digest of the transcript the user compared out of band; when a later roster
 * digests differently — or can no longer form a direct transcript at all —
 * the row flips to CHANGED inside one transaction and stays CHANGED — even
 * if the old roster comes back — until {@link #markVerified} binds a new
 * transcript. Rows are keyed by owner account so one signed-in user can
 * never read or satisfy another's verification.
 */
@Singleton
public final class KeysetTrustStore {

    /** Persisted row states; anything not VERIFIED blocks matched sends. */
    public enum TrustState { VERIFIED, CHANGED }

    /** The outcome of an encrypt-time roster comparison. */
    public enum CheckResult { VERIFIED_MATCH, UNVERIFIED, CHANGED }

    /**
     * The outcome of an encrypt-time check on a roster that produced no
     * direct transcript: UNTRACKED means no trust record exists for the
     * pair, CHANGED means a record exists and the shape change is one.
     */
    public enum ShapeResult { UNTRACKED, CHANGED }

    /** A snapshot of the stored record for one owner/conversation pair. */
    public record Record(@NonNull TrustState state, @NonNull String verifiedDigestHex,
            @NonNull byte[] verifiedTranscript, @Nullable String changedDigestHex,
            long updatedAtMs) {
        public Record {
            verifiedTranscript = verifiedTranscript.clone();
        }

        @Override
        public byte[] verifiedTranscript() {
            return verifiedTranscript.clone();
        }
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final KeysetTrustDao dao;

    @Inject
    public KeysetTrustStore(@NonNull KeysetTrustDao dao) {
        this.dao = dao;
    }

    /**
     * Pins {@code transcript} — the exact bytes the user compared — as the
     * verified keyset for the pair, overwriting any prior state.
     */
    @NonNull
    public Completable markVerified(@NonNull String ownerUserId,
            @NonNull String conversationId, @NonNull byte[] transcript) {
        byte[] pinned = transcript.clone();
        return Completable.fromAction(() -> dao.put(new KeysetTrustEntity(
                        ownerUserId, conversationId, KeysetTrustDao.STATE_VERIFIED,
                        hex(KeysetCheck.digest(pinned)), pinned, null,
                        System.currentTimeMillis())))
                .subscribeOn(Schedulers.io());
    }

    /** The stored record, or empty when the pair was never verified. */
    @NonNull
    public Single<Optional<Record>> state(@NonNull String ownerUserId,
            @NonNull String conversationId) {
        return Single.fromCallable(() -> {
                    KeysetTrustEntity row = dao.find(ownerUserId, conversationId);
                    return Optional.ofNullable(row == null ? null : toRecord(row));
                })
                .subscribeOn(Schedulers.io());
    }

    /**
     * Compares {@code transcript} — the exact roster about to receive
     * envelopes — against the stored record. A VERIFIED row whose digest
     * differs is atomically rewritten as CHANGED with the observed digest;
     * the check never clears CHANGED back down on its own.
     */
    @NonNull
    public Single<CheckResult> checkBeforeEncrypt(@NonNull String ownerUserId,
            @NonNull String conversationId, @NonNull byte[] transcript) {
        return Single.fromCallable(() -> {
                    String observed = hex(KeysetCheck.digest(transcript));
                    KeysetTrustEntity row = dao.checkAndMarkChanged(ownerUserId,
                            conversationId, observed, System.currentTimeMillis());
                    if (row == null) {
                        return CheckResult.UNVERIFIED;
                    }
                    return KeysetTrustDao.STATE_VERIFIED.equals(row.state)
                            ? CheckResult.VERIFIED_MATCH : CheckResult.CHANGED;
                })
                .subscribeOn(Schedulers.io());
    }

    /**
     * Checks the pair when the roster about to receive envelopes is not a
     * direct chat, so no transcript exists to compare: a stored record
     * means the verified shape was reshaped — itself a keyset change — so
     * the row flips to CHANGED atomically and the call reports CHANGED. A
     * missing row reports UNTRACKED, leaving groups and never-verified
     * conversations on their previous behavior.
     */
    @NonNull
    public Single<ShapeResult> checkUntrackedShape(@NonNull String ownerUserId,
            @NonNull String conversationId) {
        return Single.fromCallable(() -> {
                    KeysetTrustEntity row = dao.checkAndMarkReshaped(ownerUserId,
                            conversationId, System.currentTimeMillis());
                    return row == null ? ShapeResult.UNTRACKED : ShapeResult.CHANGED;
                })
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    private static Record toRecord(@NonNull KeysetTrustEntity row) {
        TrustState state = KeysetTrustDao.STATE_VERIFIED.equals(row.state)
                ? TrustState.VERIFIED : TrustState.CHANGED;
        return new Record(state, row.verifiedDigestHex, row.verifiedTranscript,
                row.changedDigestHex, row.updatedAtMs);
    }

    /** Uppercase hex, matching the {@link KeysetCheck#displayCode} convention. */
    @NonNull
    private static String hex(@NonNull byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }
}
