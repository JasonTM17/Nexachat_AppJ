package com.nexachat.data;

import com.nexachat.core.database.ConversationWatermark;
import com.nexachat.core.database.SyncCursorEntity;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.SyncRequest;
import com.nexachat.core.network.dto.SyncResponse;
import io.reactivex.rxjava3.core.Single;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Bounded HTTP drain. Every accepted page is durable before constructing its continuation. */
final class SyncV2Consumer {
    private final SyncPageStore store;
    private final TokenRefresher refresher;
    private final Function<SyncRequest, Single<SyncResponse>> fetch;
    private final SyncDrainProbe probe;

    SyncV2Consumer(SyncPageStore store, TokenRefresher refresher,
            Function<SyncRequest, Single<SyncResponse>> fetch, SyncDrainProbe probe) {
        this.store = store;
        this.refresher = refresher;
        this.fetch = fetch;
        this.probe = probe;
    }

    /**
     * Change rows requested per conversation per page (issue #266 Fix C).
     * Raised 50 → 200 so a 2,000-change conversation drains in 10 pages
     * instead of 40; the server clamp mirrors it
     * ({@code MessageService.SYNC_V2_CHANGE_PAGE_MAX}). Shared with
     * {@link SyncPageStore}'s validator bound.
     */
    static final int CHANGE_PAGE_LIMIT = 200;

    /**
     * Response-size guard paired with the page-size bump (issue #266): one
     * page stays under ~{@value} change rows so the commit transaction is
     * bounded. The per-request limit is adaptive —
     * {@code min(CHANGE_PAGE_LIMIT, budget / watermarks)} — so a full
     * 100-watermark batch keeps the legacy 50 rows/conversation while the
     * {@code wm=1} continuation pages that dominate measured drains fetch
     * the full 200.
     */
    static final int TOTAL_RESPONSE_ROWS_BUDGET = 5000;
    static final int WATERMARKS_PER_PAGE = 100;
    static final int MIN_PAGE_LIMIT = 50;

    boolean sync(String owner, long epoch, List<ConversationWatermark> watermarks) throws Exception {
        boolean pending = false;
        List<ConversationWatermark> ordered = new ArrayList<>(watermarks);
        ordered.sort(Comparator.comparing(ConversationWatermark::getConversationId));
        Map<String, Long> seqs = new HashMap<>();
        ordered.forEach(w -> seqs.put(w.getConversationId(), w.getMaxSeq()));
        for (int start = 0; start < ordered.size(); start += WATERMARKS_PER_PAGE) {
            List<String> next = new ArrayList<>();
            for (ConversationWatermark watermark : ordered.subList(start, Math.min(start + WATERMARKS_PER_PAGE, ordered.size()))) {
                next.add(watermark.getConversationId());
            }
            int budget = SyncEngine.MAX_DRAIN_PAGES * (SyncEngine.MAX_BOUNDED_RETRIES + 1);
            for (int page = 0; page < budget && !next.isEmpty(); page++) {
                if (!refresher.isSessionCurrent(epoch)) throw new CancellationException("Sync session changed");
                Map<String, SyncCursorEntity> snapshots = new LinkedHashMap<>();
                List<SyncRequest.Watermark> requested = new ArrayList<>();
                String since = null;
                boolean missingSince = false;
                for (String conversation : next) {
                    SyncCursorEntity cursor = store.read(owner, conversation);
                    snapshots.put(conversation, cursor);
                    requested.add(new SyncRequest.Watermark(conversation, seqs.get(conversation),
                            cursor.reactionCursor, cursor.changeCursor,
                            cursor.generation > 0 ? cursor.generation : null));
                    if (cursor.reactionSince == null) missingSince = true;
                    else if (since == null || java.time.Instant.parse(cursor.reactionSince)
                            .isBefore(java.time.Instant.parse(since))) since = cursor.reactionSince;
                }
                int pageLimit = Math.max(MIN_PAGE_LIMIT,
                        Math.min(CHANGE_PAGE_LIMIT, TOTAL_RESPONSE_ROWS_BUDGET / next.size()));
                SyncRequest request = new SyncRequest(requested, pageLimit, missingSince ? null : since, 2);
                // Drain split timer (issue #266 phase 0): HTTP fetch vs local apply per page.
                boolean timed = probe.recording();
                long fetchStart = timed ? System.nanoTime() : 0L;
                SyncResponse response = fetch.apply(request).blockingGet();
                long fetchNanos = timed ? System.nanoTime() - fetchStart : 0L;
                SyncPageStore.validate(response, snapshots);
                long applyStart = timed ? System.nanoTime() : 0L;
                next = store.apply(owner, epoch, snapshots, response);
                long applyNanos = timed ? System.nanoTime() - applyStart : 0L;
                probe.onPage(requested.size(), response, next.size(), fetchNanos, applyNanos);
            }
            if (!next.isEmpty()) pending = true;
        }
        return pending;
    }
}
