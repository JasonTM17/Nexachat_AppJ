package com.nexachat.feature.archive;

import androidx.annotation.NonNull;
import com.nexachat.core.common.Result;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.GroupDetail;
import com.nexachat.data.GroupMember;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Reuses the inbox member payload already used by group details. */
public final class ConversationArchiveSenderSource implements ArchiveSenderSource {

    private final ConversationRepository conversations;

    public ConversationArchiveSenderSource(@NonNull ConversationRepository conversations) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
    }

    @Override
    public Cancellable load(String conversationId, Callback callback) {
        Disposable request = conversations.groupDetail(conversationId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> callback.onLoaded(map(result)), ignored ->
                        callback.onLoaded(Collections.emptyList()));
        return request::dispose;
    }

    private static List<ArchiveSender> map(Result<GroupDetail> result) {
        if (!(result instanceof Result.Success<?> success)
                || !(success.getValue() instanceof GroupDetail detail)) {
            return Collections.emptyList();
        }
        List<ArchiveSender> senders = new ArrayList<>();
        for (GroupMember member : detail.getMembers()) {
            senders.add(new ArchiveSender(member.getUserId(), member.getDisplayName()));
        }
        return List.copyOf(senders);
    }
}
