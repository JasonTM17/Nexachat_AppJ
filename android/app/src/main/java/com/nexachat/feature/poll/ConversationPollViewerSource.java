package com.nexachat.feature.poll;

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

/** Reuses the group detail projection for voter names and the caller's admin role. */
public final class ConversationPollViewerSource implements PollViewerSource {

    private final ConversationRepository conversations;

    public ConversationPollViewerSource(@NonNull ConversationRepository conversations) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
    }

    @Override
    @NonNull
    public Cancellable load(@NonNull String conversationId, @NonNull Callback callback) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(callback, "callback");
        Disposable request = conversations.myUserId()
                .defaultIfEmpty("")
                .flatMap(myUserId -> conversations.groupDetail(conversationId)
                        .map(result -> new Load(myUserId, map(result))))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        load -> callback.onLoaded(load.members, blankToNull(load.myUserId)),
                        ignored -> callback.onLoaded(Collections.emptyList(), null));
        return request::dispose;
    }

    private static List<PollViewerSource.Member> map(Result<GroupDetail> result) {
        if (!(result instanceof Result.Success<?> success)
                || !(success.getValue() instanceof GroupDetail detail)) {
            return Collections.emptyList();
        }
        List<PollViewerSource.Member> members = new ArrayList<>();
        for (GroupMember member : detail.getMembers()) {
            members.add(new PollViewerSource.Member(
                    member.getUserId(), member.getDisplayName(), member.isAdmin()));
        }
        return List.copyOf(members);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class Load {
        final String myUserId;
        final List<PollViewerSource.Member> members;

        Load(String myUserId, List<PollViewerSource.Member> members) {
            this.myUserId = myUserId;
            this.members = members;
        }
    }
}
