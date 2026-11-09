package com.nexachat.feature.label;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Set;

/** Network and Room boundaries used by the labels UI. */
public interface LabelRepository {
    Single<List<ConversationLabel>> listLabels();
    Single<ConversationLabel> createLabel(String name, LabelColor color);
    Single<ConversationLabel> updateLabel(String labelId, String name, LabelColor color);
    Completable deleteLabel(String labelId);
    Completable assignLabel(String conversationId, String labelId);
    Completable unassignLabel(String conversationId, String labelId);
    Single<Set<String>> filterConversationIds(String labelId);
    Single<List<String>> cachedLabelIds(String conversationId);
    Completable cacheLabelIds(String conversationId, List<String> labelIds);
    Completable removeLabelFromCache(String labelId);
}
