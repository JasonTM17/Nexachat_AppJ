package com.nexachat.feature.inbox;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.ConversationRepository;
import com.squareup.moshi.Moshi;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.Objects;
import javax.inject.Inject;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/**
 * Backs InboxScreen (plan §12.1 feature/inbox). The list is a pure Room stream
 * via {@link ConversationRepository#inboxFlow()} — WS pushes only trigger repository
 * refreshes, so the VM never handles frames. Unread badges come straight from
 * {@code unreadCount}; {@link InboxUiState#getTotalUnread()} feeds any app-level badge.
 */
@HiltViewModel
public final class InboxViewModel extends ViewModel {

    private final ConversationRepository conversationRepository;
    private final MutableStateFlow<InboxUiState> mutableUiState =
            StateFlowKt.MutableStateFlow(new InboxUiState());
    private final StateFlow<InboxUiState> uiState = mutableUiState;

    @Inject
    public InboxViewModel(
            ConversationRepository conversationRepository,
            @Nullable TokenStore tokenStore,
            @Nullable Moshi moshi) {
        this.conversationRepository =
                Objects.requireNonNull(conversationRepository, "conversationRepository");
        start(tokenStore, moshi);
    }

    /** Test constructor — no token/JWT resolution. */
    public InboxViewModel(ConversationRepository conversationRepository) {
        this.conversationRepository =
                Objects.requireNonNull(conversationRepository, "conversationRepository");
        start(null, null);
    }

    public StateFlow<InboxUiState> getUiState() {
        return uiState;
    }

    /** Pulls the authoritative inbox from REST (launch + user refresh). */
    public void refresh() {
        CoroutineScope scope = ViewModelKt.getViewModelScope(this);
        BuildersKt.launch(
                scope,
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                new Function2<CoroutineScope, Continuation<? super Unit>, Object>() {
                    @Override
                    public Object invoke(
                            CoroutineScope coroutineScope, Continuation<? super Unit> continuation) {
                        return InboxViewModelSupportKt.inboxRefreshInbox(
                                conversationRepository, mutableUiState, continuation);
                    }
                });
    }

    private void start(@Nullable TokenStore tokenStore, @Nullable Moshi moshi) {
        CoroutineScope scope = ViewModelKt.getViewModelScope(this);
        if (tokenStore != null && moshi != null) {
            TokenStore store = tokenStore;
            Moshi json = moshi;
            BuildersKt.launch(
                    scope,
                    EmptyCoroutineContext.INSTANCE,
                    CoroutineStart.DEFAULT,
                    new Function2<CoroutineScope, Continuation<? super Unit>, Object>() {
                        @Override
                        public Object invoke(
                                CoroutineScope coroutineScope,
                                Continuation<? super Unit> continuation) {
                            return InboxViewModelSupportKt.inboxLoadCurrentUserId(
                                    store, json, mutableUiState, continuation);
                        }
                    });
        }
        BuildersKt.launch(
                scope,
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                new Function2<CoroutineScope, Continuation<? super Unit>, Object>() {
                    @Override
                    public Object invoke(
                            CoroutineScope coroutineScope, Continuation<? super Unit> continuation) {
                        return InboxViewModelSupportKt.inboxCollectConversationRows(
                                conversationRepository, mutableUiState, continuation);
                    }
                });
        refresh();
    }
}
