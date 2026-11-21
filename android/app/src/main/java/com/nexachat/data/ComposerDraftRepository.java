package com.nexachat.data;

import androidx.annotation.NonNull;
import com.nexachat.core.database.ComposerDraftDao;
import com.nexachat.core.database.ComposerDraftEntity;
import com.nexachat.feature.chat.MentionDraft;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Local composer drafts keyed by conversation. */
@Singleton
public final class ComposerDraftRepository {

    private final ComposerDraftDao dao;

    @Inject
    public ComposerDraftRepository(@NonNull ComposerDraftDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    @NonNull
    public Completable save(@NonNull String conversationId, @NonNull MentionDraft draft) {
        MentionDraft trimmed = draft.trimmed();
        if (trimmed.getContent().isEmpty()) {
            return dao.delete(conversationId).subscribeOn(Schedulers.io());
        }
        ComposerDraftEntity row = new ComposerDraftEntity(
                conversationId,
                trimmed.getContent(),
                MessageMentionCodec.encode(trimmed.getMentions()),
                trimmed.getMentionAllOffset(),
                trimmed.getMentionAllLength(),
                System.currentTimeMillis());
        return dao.upsert(row).subscribeOn(Schedulers.io());
    }

    @NonNull
    public Completable clear(@NonNull String conversationId) {
        return dao.delete(conversationId).subscribeOn(Schedulers.io());
    }

    @NonNull
    public Maybe<MentionDraft> load(@NonNull String conversationId) {
        return dao.byId(conversationId)
                .map(ComposerDraftRepository::toDraft)
                .subscribeOn(Schedulers.io());
    }

    /** Non-blank draft bodies for inbox {@code Draft:} previews. */
    @NonNull
    public Flowable<Map<String, String>> observePreviewBodies() {
        return dao.observeAll()
                .map(ComposerDraftRepository::previewBodies)
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    static Map<String, String> previewBodies(@NonNull List<ComposerDraftEntity> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, String> bodies = new LinkedHashMap<>();
        for (ComposerDraftEntity row : rows) {
            String content = row.getContent() == null ? "" : row.getContent().trim();
            if (!content.isEmpty()) {
                bodies.put(row.getConversationId(), content);
            }
        }
        return Collections.unmodifiableMap(bodies);
    }

    @NonNull
    static MentionDraft toDraft(@NonNull ComposerDraftEntity row) {
        return MentionDraft.restore(
                row.getContent() == null ? "" : row.getContent(),
                MessageMentionCodec.decode(row.getMentionsJson()),
                row.getMentionAllOffset(),
                row.getMentionAllLength());
    }
}
