package com.nexachat.data;

import com.nexachat.core.database.MessageReactionEntity;
import com.nexachat.core.database.OfflineOutboxDao;
import com.nexachat.core.database.OfflineOutboxEntity;
import com.nexachat.core.database.PinnedMessageEntity;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * In-memory {@link OfflineOutboxDao} double. State is plain lists so a test can
 * snapshot and restore it around a simulated Room transaction.
 */
public final class FakeOfflineOutboxDao implements OfflineOutboxDao {
    private final List<OfflineOutboxEntity> rows = new ArrayList<>();
    private final List<MessageReactionEntity> reactions = new ArrayList<>();
    private final List<PinnedMessageEntity> pins = new ArrayList<>();
    /** Ordered log of mutating operations; used to assert transaction boundaries. */
    public final List<String> operations = new ArrayList<>();
    /** When true the next {@link #insertBlocking} throws, simulating an encode failure. */
    public boolean failNextInsertBlocking;
    /** Incremented by the test while the simulated Room transaction body runs. */
    public int transactionDepth;

    public FakeOfflineOutboxDao() {
    }

    /** Deep copy of all state so a failing transaction can be rolled back. */
    public Object snapshot() {
        return List.of(new ArrayList<>(rows), new ArrayList<>(reactions), new ArrayList<>(pins));
    }

    /** Restores a snapshot taken before a failed transaction body. */
    @SuppressWarnings("unchecked")
    public void restore(Object snapshot) {
        List<List<?>> state = (List<List<?>>) snapshot;
        rows.clear();
        rows.addAll((List<OfflineOutboxEntity>) state.get(0));
        reactions.clear();
        reactions.addAll((List<MessageReactionEntity>) state.get(1));
        pins.clear();
        pins.addAll((List<PinnedMessageEntity>) state.get(2));
    }

    public List<OfflineOutboxEntity> rows() {
        return new ArrayList<>(rows);
    }

    public List<MessageReactionEntity> reactions() {
        return new ArrayList<>(reactions);
    }

    public List<PinnedMessageEntity> pins() {
        return new ArrayList<>(pins);
    }

    private static List<OfflineOutboxEntity> sorted(List<OfflineOutboxEntity> source) {
        List<OfflineOutboxEntity> copy = new ArrayList<>(source);
        copy.sort(Comparator.comparingLong(OfflineOutboxEntity::getCreatedAtEpochMs)
                .thenComparing(OfflineOutboxEntity::getId));
        return copy;
    }

    private OfflineOutboxEntity rowById(String id) {
        for (OfflineOutboxEntity row : rows) if (row.getId().equals(id)) return row;
        return null;
    }

    private static boolean claimable(OfflineOutboxEntity row, long staleThreshold) {
        if ("PENDING".equals(row.getState()) || "SENDING".equals(row.getState())) return true;
        return "IN_PROGRESS".equals(row.getState()) && row.getLastAttemptEpochMs() < staleThreshold;
    }

    @Override public Completable insert(OfflineOutboxEntity item) {
        return Completable.fromAction(() -> insertBlocking(item));
    }

    @Override public void insertBlocking(OfflineOutboxEntity item) {
        operations.add("insertBlocking:" + item.getId() + ":" + transactionDepth);
        if (failNextInsertBlocking) {
            failNextInsertBlocking = false;
            throw new IllegalStateException("simulated encode failure");
        }
        rows.removeIf(row -> row.getId().equals(item.getId()));
        rows.add(item);
    }

    @Override public Completable insertAll(List<OfflineOutboxEntity> items) {
        return Completable.fromAction(() -> {
            for (OfflineOutboxEntity item : items) insertBlocking(item);
        });
    }

    @Override public Single<Integer> claimLeases(long now, long staleThreshold) {
        return Single.fromCallable(() -> {
            int claimed = 0;
            for (OfflineOutboxEntity row : rows) {
                if (claimable(row, staleThreshold)) {
                    rows.set(rows.indexOf(row), row.withStateAndAttempt("IN_PROGRESS", row.getRetryCount(), now));
                    claimed++;
                }
            }
            return claimed;
        });
    }

    @Override public Single<Integer> claimLease(String id, long now, long staleThreshold) {
        return Single.fromCallable(() -> {
            OfflineOutboxEntity row = rowById(id);
            if (row == null || !claimable(row, staleThreshold)) return 0;
            rows.set(rows.indexOf(row), row.withStateAndAttempt("IN_PROGRESS", row.getRetryCount(), now));
            return 1;
        });
    }

    @Override public Single<List<OfflineOutboxEntity>> getClaimableItems(long staleThreshold) {
        return Single.fromCallable(() -> {
            List<OfflineOutboxEntity> result = new ArrayList<>();
            for (OfflineOutboxEntity row : rows) if (claimable(row, staleThreshold)) result.add(row);
            return sorted(result);
        });
    }

    @Override public List<OfflineOutboxEntity> getAllOrderedBlocking() {
        return sorted(rows);
    }

    @Override public OfflineOutboxEntity findByIdBlocking(String id) {
        return rowById(id);
    }

    @Override public Maybe<OfflineOutboxEntity> findById(String id) {
        return Maybe.fromCallable(() -> rowById(id));
    }

    @Override public Maybe<OfflineOutboxEntity> findByIdempotencyKey(String idempotencyKey) {
        return Maybe.fromCallable(() -> {
            for (OfflineOutboxEntity row : rows) {
                if (row.getIdempotencyKey().equals(idempotencyKey)) return row;
            }
            return null;
        });
    }

    @Override public Completable updateState(String id, String state, int retryCount, long lastAttemptEpochMs) {
        return Completable.fromAction(() -> {
            OfflineOutboxEntity row = rowById(id);
            if (row != null) {
                rows.set(rows.indexOf(row),
                        row.withStateAndAttempt(state, retryCount, lastAttemptEpochMs));
            }
        });
    }

    @Override public int updatePayloadBlocking(String id, String payloadJson) {
        OfflineOutboxEntity row = rowById(id);
        if (row == null) return 0;
        String state = row.getState();
        if (!"PENDING".equals(state) && !"SENDING".equals(state) && !"IN_PROGRESS".equals(state)) return 0;
        rows.set(rows.indexOf(row), new OfflineOutboxEntity(row.getId(), row.getIdempotencyKey(),
                row.getActionType(), payloadJson, state, row.getRetryCount(),
                row.getCreatedAtEpochMs(), row.getLastAttemptEpochMs()));
        return 1;
    }

    @Override public Completable markSent(String id, long now) {
        return Completable.fromAction(() -> {
            OfflineOutboxEntity row = rowById(id);
            if (row != null) {
                rows.set(rows.indexOf(row), row.withStateAndAttempt("SENT", row.getRetryCount(), now));
            }
        });
    }

    @Override public int markSentBlocking(String id, long now) {
        OfflineOutboxEntity row = rowById(id);
        if (row == null || !"IN_PROGRESS".equals(row.getState())) return 0;
        rows.set(rows.indexOf(row), row.withStateAndAttempt("SENT", row.getRetryCount(), now));
        return 1;
    }

    @Override public Completable markFailed(String id, long now) {
        return Completable.fromAction(() -> {
            OfflineOutboxEntity row = rowById(id);
            if (row != null) {
                rows.set(rows.indexOf(row), row.withStateAndAttempt("FAILED", row.getRetryCount(), now));
            }
        });
    }

    @Override public int markFailedBlocking(String id, long now) {
        OfflineOutboxEntity row = rowById(id);
        if (row == null || !"IN_PROGRESS".equals(row.getState())) return 0;
        rows.set(rows.indexOf(row), row.withStateAndAttempt("FAILED", row.getRetryCount(), now));
        return 1;
    }

    @Override public Completable releaseLease(String id, int retryCount, String payloadJson) {
        return Completable.fromAction(() -> {
            operations.add("releaseLease:" + id + ":" + retryCount);
            OfflineOutboxEntity row = rowById(id);
            if (row == null || !"IN_PROGRESS".equals(row.getState())) return;
            rows.set(rows.indexOf(row), new OfflineOutboxEntity(row.getId(), row.getIdempotencyKey(),
                    row.getActionType(), payloadJson, "PENDING", retryCount,
                    row.getCreatedAtEpochMs(), 0L));
        });
    }

    @Override public Completable deleteById(String id) {
        return Completable.fromAction(() -> deleteByIdBlocking(id));
    }

    @Override public int deleteByIdBlocking(String id) {
        operations.add("deleteByIdBlocking:" + id);
        return rows.removeIf(row -> row.getId().equals(id)) ? 1 : 0;
    }

    @Override public Completable deleteByIdempotencyKey(String idempotencyKey) {
        return Completable.fromAction(() -> rows.removeIf(
                row -> row.getIdempotencyKey().equals(idempotencyKey)));
    }

    @Override public void clearBlocking() {
        rows.clear();
    }

    @Override public List<MessageReactionEntity> getReactionsBlocking(String messageId) {
        List<MessageReactionEntity> result = new ArrayList<>();
        for (MessageReactionEntity row : reactions) {
            if (row.getMessageId().equals(messageId)) result.add(row);
        }
        return result;
    }

    @Override public void deleteReactionsBlocking(String messageId) {
        operations.add("deleteReactionsBlocking:" + messageId + ":" + transactionDepth);
        reactions.removeIf(row -> row.getMessageId().equals(messageId));
    }

    @Override public void insertReactionsBlocking(List<MessageReactionEntity> newReactions) {
        operations.add("insertReactionsBlocking:" + newReactions.size() + ":" + transactionDepth);
        for (MessageReactionEntity row : newReactions) {
            reactions.removeIf(existing -> existing.getMessageId().equals(row.getMessageId())
                    && existing.getEmoji().equals(row.getEmoji()));
            reactions.add(row);
        }
    }

    @Override public PinnedMessageEntity findPinBlocking(String conversationId, String messageId) {
        for (PinnedMessageEntity row : pins) {
            if (row.getConversationId().equals(conversationId) && row.getMessageId().equals(messageId)) {
                return row;
            }
        }
        return null;
    }

    @Override public void insertPinBlocking(PinnedMessageEntity pin) {
        operations.add("insertPinBlocking:" + pin.getMessageId() + ":" + transactionDepth);
        pins.removeIf(existing -> existing.getConversationId().equals(pin.getConversationId())
                && existing.getMessageId().equals(pin.getMessageId()));
        pins.add(pin);
    }

    @Override public void deletePinBlocking(String conversationId, String messageId) {
        operations.add("deletePinBlocking:" + messageId + ":" + transactionDepth);
        pins.removeIf(row -> row.getConversationId().equals(conversationId)
                && row.getMessageId().equals(messageId));
    }

    @Override public Flowable<List<OfflineOutboxEntity>> getAllOrdered() {
        return Flowable.fromCallable(() -> sorted(rows));
    }

    @Override public Single<List<OfflineOutboxEntity>> getAllOrderedList() {
        return Single.fromCallable(() -> sorted(rows));
    }

    @Override public Flowable<Integer> countActive() {
        return Flowable.fromCallable(() -> {
            int count = 0;
            for (OfflineOutboxEntity row : rows) {
                if (!"FAILED".equals(row.getState()) && !"SENT".equals(row.getState())) count++;
            }
            return count;
        });
    }

    @Override public Completable clear() {
        return Completable.fromAction(this::clearBlocking);
    }

    /** Convenience for tests: the single active row for a message, or {@code null}. */
    public OfflineOutboxEntity onlyRow() {
        return rows.size() == 1 ? rows.get(0) : null;
    }

    /** Convenience for tests: the single reaction row for an emoji, or {@code null}. */
    public MessageReactionEntity reaction(String emoji) {
        for (MessageReactionEntity row : reactions) {
            if (row.getEmoji().equals(emoji)) return row;
        }
        return null;
    }

    @Override public String toString() {
        return "FakeOfflineOutboxDao(rows=" + rows + ", reactions=" + reactions + ", pins=" + pins + ")";
    }
}
