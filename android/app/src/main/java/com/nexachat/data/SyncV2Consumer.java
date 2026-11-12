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

    SyncV2Consumer(SyncPageStore store, TokenRefresher refresher,
            Function<SyncRequest, Single<SyncResponse>> fetch) {
        this.store = store;
        this.refresher = refresher;
        this.fetch = fetch;
    }

    boolean sync(String owner, long epoch, List<ConversationWatermark> watermarks) throws Exception {
        boolean pending = false;
        List<ConversationWatermark> ordered = new ArrayList<>(watermarks);
        ordered.sort(Comparator.comparing(ConversationWatermark::getConversationId));
        Map<String, Long> seqs = new HashMap<>();
        ordered.forEach(w -> seqs.put(w.getConversationId(), w.getMaxSeq()));
        for (int start = 0; start < ordered.size(); start += 100) {
            List<String> next = new ArrayList<>();
            for (ConversationWatermark watermark : ordered.subList(start, Math.min(start + 100, ordered.size()))) {
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
                SyncRequest request = new SyncRequest(requested, 50, missingSince ? null : since, 2);
                SyncResponse response = fetch.apply(request).blockingGet();
                SyncPageStore.validate(response, snapshots);
                next = store.apply(owner, epoch, snapshots, response);
            }
            if (!next.isEmpty()) pending = true;
        }
        return pending;
    }
}
