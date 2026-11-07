package com.nexachat.feature.chat;

import android.net.Uri;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.data.AttachmentRepository;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.data.MessageRepository;
import com.nexachat.data.PeerPresence;
import com.nexachat.feature.media.PendingAttachment;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/** Backs {@code chat/{conversationId}}. State and synchronous UI rules live in Java. */
@HiltViewModel
public final class ChatViewModel extends ViewModel {

    public static final long TYPING_SEND_INTERVAL_MS = 3_000L;
    public static final long PEER_TYPING_TTL_MS = 5_000L;
    public static final int MIN_SEARCH_CHARS = 2;
    public static final long SEARCH_DEBOUNCE_MS = 300L;
    public static final int SEARCH_PAGE_SIZE = MessageApi.DEFAULT_SEARCH_LIMIT;
    public static final String ERROR_MESSAGE_GONE = "MESSAGE_NOT_FOUND";
    static final String KEY_INPUT = "chat_input";

    private final String conversationId;
    private final MessageRepository messageRepository;
    private final MutableStateFlow<UiState> mutableUiState =
            StateFlowKt.MutableStateFlow(new UiState());
    private final MutableStateFlow<ChatSearchState> mutableSearchState =
            StateFlowKt.MutableStateFlow(new ChatSearchState());
    private final MutableStateFlow<ApiError> error = StateFlowKt.MutableStateFlow(null);
    private final MutableStateFlow<Boolean> loading = StateFlowKt.MutableStateFlow(true);
    private final MutableStateFlow<Boolean> peerTyping = StateFlowKt.MutableStateFlow(false);
    private final MutableStateFlow<Boolean> hasMore = StateFlowKt.MutableStateFlow(false);
    private final MutableStateFlow<List<PendingAttachment>> pendingAttachments =
            StateFlowKt.MutableStateFlow(List.of());
    private final MutableStateFlow<ReplyTarget> replyTarget = StateFlowKt.MutableStateFlow(null);
    private final StateFlow<String> input;
    private final StateFlow<UiState> uiState = mutableUiState;
    private final StateFlow<ChatSearchState> searchState = mutableSearchState;
    private final ChatViewModelSupport support;

    @Inject
    public ChatViewModel(
            SavedStateHandle savedStateHandle,
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            AttachmentRepository attachmentRepository,
            RealtimeSession session) {
        Objects.requireNonNull(savedStateHandle, "savedStateHandle");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        String savedConversationId = savedStateHandle.get("conversationId");
        this.conversationId = savedConversationId == null ? "" : savedConversationId;
        this.input = savedStateHandle.getStateFlow(KEY_INPUT, "");
        this.support = new ChatViewModelSupport(
                conversationId,
                savedStateHandle,
                messageRepository,
                Objects.requireNonNull(conversationRepository, "conversationRepository"),
                Objects.requireNonNull(attachmentRepository, "attachmentRepository"),
                Objects.requireNonNull(session, "session"),
                ViewModelKt.getViewModelScope(this),
                mutableUiState,
                mutableSearchState,
                input,
                loading,
                error,
                peerTyping,
                hasMore,
                pendingAttachments,
                replyTarget);
        support.start();
    }

    public StateFlow<UiState> getUiState() { return uiState; }
    public StateFlow<ChatSearchState> getSearchState() { return searchState; }
    public kotlinx.coroutines.flow.SharedFlow<ChatActionEvent> getActionEvents() {
        return support.getActionEvents();
    }
    public kotlinx.coroutines.flow.SharedFlow<String> getJumpTargets() {
        return support.getJumpTargets();
    }

    public void onInputChange(String value) { support.onInputChange(value); }
    public void send() { support.send(); }
    public void retryFailed(String clientMessageId) { support.retryFailed(clientMessageId); }
    public void jumpToMessage(String messageId) { support.jumpToMessage(messageId); }

    public boolean canEdit(ChatMessage message) {
        return message.getMine()
                && message.getStatus() == DeliveryStatus.SENT
                && !message.isDeleted()
                && MessageDto.TYPE_TEXT.equals(message.getType());
    }

    public boolean canDelete(ChatMessage message) {
        return message.getMine()
                && message.getStatus() == DeliveryStatus.SENT
                && !message.isDeleted();
    }

    public void editMessage(String messageId, String newContent) {
        support.editMessage(messageId, newContent);
    }
    public void deleteMessage(String messageId) { support.deleteMessage(messageId); }
    public void forwardMessage(String messageId, String targetConversationId) {
        support.forwardMessage(messageId, targetConversationId);
    }
    public void search(String query) { support.search(query); }
    public void loadMoreSearch() { support.loadMoreSearch(); }
    public void clearSearch() { support.clearSearch(); }

    public void setReplyTarget(ChatMessage message) {
        if (message.getStatus() != DeliveryStatus.SENT) return;
        replyTarget.setValue(new ReplyTarget(
                message.getId(),
                message.getSenderDisplayName(),
                message.getContent() != null ? message.getContent() : message.getType()));
    }

    public void clearReplyTarget() { replyTarget.setValue(null); }
    public void toggleReaction(ChatMessage message, String emoji) {
        support.toggleReaction(message, emoji);
    }
    public void togglePin(ChatMessage message) {
        if (message.getStatus() == DeliveryStatus.SENT) support.togglePin(message);
    }
    public void sendAttachments(List<Uri> uris) { support.sendAttachments(uris); }
    public void removePendingAttachment(String localId) { support.removePendingAttachment(localId); }
    public void loadOlder() { support.loadOlder(); }

    @Override
    protected void onCleared() {
        messageRepository.closeConversation(conversationId);
        super.onCleared();
    }

    ChatViewModelSupport supportSession() { return support; }

    /** In-conversation search state; results are hydrated and newest-first. */
    public record ChatSearchState(
            String query,
            boolean loading,
            List<ChatMessage> results,
            boolean hasMore,
            ApiError error) {
        public ChatSearchState() { this("", false, List.of(), false, null); }
        public ChatSearchState {
            query = Objects.requireNonNull(query, "query");
            results = Objects.requireNonNull(results, "results");
        }
    }

    /** Reply draft displayed above the composer. */
    public record ReplyTarget(String messageId, String senderName, String snippet) {}

    /** One-shot action results surfaced by the screen. */
    public sealed interface ChatActionEvent
            permits ChatActionEvent.MessageForwarded, ChatActionEvent.MessageEdited {
        record MessageForwarded(String targetConversationId) implements ChatActionEvent {}
        record MessageEdited(String messageId) implements ChatActionEvent {}
    }

    /** Immutable UI snapshot. Coroutine support updates it from repository flows. */
    public record UiState(
            boolean loading,
            List<ChatMessage> messages,
            List<ChatMessage> sending,
            String input,
            boolean peerTyping,
            String peerName,
            boolean isGroup,
            boolean canPin,
            boolean connected,
            boolean hasMore,
            ApiError error,
            List<PendingAttachment> pendingAttachments,
            ReplyTarget replyTarget,
            List<ChatMessage> pins,
            long peerReadSeq,
            PeerPresence peerPresence,
            String otherUserId) {
        public UiState() {
            this(true, List.of(), List.of(), "", false, null, false, true, true, false,
                    null, List.of(), null, List.of(), 0L, null, null);
        }

        public static UiState fromBase(
                List<ChatMessage> messages,
                ConversationSummary conversation,
                String input,
                boolean peerTyping,
                ApiError error) {
            List<ChatMessage> sending = new ArrayList<>();
            for (ChatMessage message : messages) {
                if (message.getStatus() != DeliveryStatus.SENT) sending.add(message);
            }
            boolean isGroup = conversation != null && conversation.isGroup();
            String peerName = conversation == null ? null : conversation.getDisplayName();
            if (peerName != null && peerName.isBlank()) peerName = null;
            String otherUserId = conversation == null ? null : conversation.getOtherUserId();
            if (otherUserId != null && otherUserId.isBlank()) otherUserId = null;
            return new UiState(
                    true, messages, sending, input, peerTyping, peerName, isGroup,
                    !isGroup || conversation != null && "ADMIN".equals(conversation.getMyRole()),
                    true, false, error, List.of(), null, List.of(),
                    conversation == null ? 0L : conversation.getPeerReadSeq(),
                    conversation == null ? null : conversation.getPeerPresence(), otherUserId);
        }

        public UiState withConnected(boolean value) {
            return new UiState(loading, messages, sending, input, peerTyping, peerName,
                    isGroup, canPin, value, hasMore, error, pendingAttachments, replyTarget,
                    pins, peerReadSeq, peerPresence, otherUserId);
        }
        public UiState withHasMore(boolean value) {
            return new UiState(loading, messages, sending, input, peerTyping, peerName,
                    isGroup, canPin, connected, value, error, pendingAttachments, replyTarget,
                    pins, peerReadSeq, peerPresence, otherUserId);
        }
        public UiState withLoading(boolean value) {
            return new UiState(value, messages, sending, input, peerTyping, peerName,
                    isGroup, canPin, connected, hasMore, error, pendingAttachments, replyTarget,
                    pins, peerReadSeq, peerPresence, otherUserId);
        }
        public UiState withPendingAttachments(List<PendingAttachment> value) {
            return new UiState(loading, messages, sending, input, peerTyping, peerName,
                    isGroup, canPin, connected, hasMore, error, value, replyTarget,
                    pins, peerReadSeq, peerPresence, otherUserId);
        }
        public UiState withReplyTarget(ReplyTarget value) {
            return new UiState(loading, messages, sending, input, peerTyping, peerName,
                    isGroup, canPin, connected, hasMore, error, pendingAttachments, value,
                    pins, peerReadSeq, peerPresence, otherUserId);
        }
        public UiState withPins(List<ChatMessage> value) {
            return new UiState(loading, messages, sending, input, peerTyping, peerName,
                    isGroup, canPin, connected, hasMore, error, pendingAttachments, replyTarget,
                    value, peerReadSeq, peerPresence, otherUserId);
        }

        public boolean isPinned(String messageId) {
            return pins.stream().anyMatch(message -> message.getId().equals(messageId));
        }
    }
}
