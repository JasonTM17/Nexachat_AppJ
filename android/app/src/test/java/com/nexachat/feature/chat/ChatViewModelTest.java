package com.nexachat.feature.chat;

import android.net.Uri;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelStore;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.MessagePageDto;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.AttachmentRepository;
import com.nexachat.data.AttachmentUploadEvent;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.data.GroupDetail;
import com.nexachat.data.GroupMember;
import com.nexachat.data.LocalAttachment;
import com.nexachat.data.MessageJump;
import com.nexachat.data.MessageRepository;
import com.nexachat.data.MessageSearchResult;
import com.nexachat.data.PeerPresence;
import com.nexachat.data.TypingEvent;
import com.nexachat.feature.media.PendingAttachment;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.nexachat.core.network.dto.MessageMention;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class ChatViewModelTest {

    private static final String CONVERSATION_ID = "c-1";
    private static final String TARGET_CONVERSATION_ID = "c-target";
    private static final AttachmentDto ATTACHMENT =
            new AttachmentDto("att-1", "pic.png", "image/png", 12L);

    @Rule
    public final TestRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final TestScheduler timerScheduler = new TestScheduler();
    private final ViewModelStore viewModelStore = new ViewModelStore();
    private int viewModelCount;
    private MessageRepository messageRepository;
    private ConversationRepository conversationRepository;
    private AttachmentRepository attachmentRepository;
    private RealtimeSession session;
    private TokenStore tokenStore;
    private BehaviorProcessor<List<ChatMessage>> messagesFlow;
    private PublishProcessor<Optional<ConversationSummary>> conversationFlow;
    private PublishProcessor<TypingEvent> typingFlow;
    private BehaviorProcessor<Boolean> isOpenFlow;
    private BehaviorProcessor<Boolean> restReachabilityFlow;
    private BehaviorProcessor<List<ChatMessage>> pinsFlow;
    private PublishProcessor<ApiError> interactionFlow;
    private ChatViewModel viewModel;
    private Uri uri;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(ignored -> timerScheduler);
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());

        messageRepository = Mockito.mock(MessageRepository.class);
        conversationRepository = Mockito.mock(ConversationRepository.class);
        attachmentRepository = Mockito.mock(AttachmentRepository.class);
        session = Mockito.mock(RealtimeSession.class);
        tokenStore = Mockito.mock(TokenStore.class);
        messagesFlow = BehaviorProcessor.createDefault(List.of());
        conversationFlow = PublishProcessor.create();
        typingFlow = PublishProcessor.create();
        isOpenFlow = BehaviorProcessor.createDefault(true);
        restReachabilityFlow = BehaviorProcessor.createDefault(false);
        pinsFlow = BehaviorProcessor.createDefault(List.of());
        interactionFlow = PublishProcessor.create();
        uri = Mockito.mock(Uri.class);

        when(messageRepository.messagesFlow(anyString())).thenReturn(messagesFlow);
        when(messageRepository.getTypingEvents()).thenReturn(typingFlow);
        when(messageRepository.pinsFlow(anyString())).thenReturn(pinsFlow);
        when(messageRepository.getInteractionEvents()).thenReturn(interactionFlow);
        when(messageRepository.getRestReachability()).thenReturn(restReachabilityFlow);
        doNothing().when(messageRepository).closeConversation(anyString());
        when(messageRepository.openConversation(anyString())).thenReturn(Completable.complete());
        when(messageRepository.refreshHistory(anyString(), nullable(Long.class)))
                .thenReturn(Single.just(new Result.Success<>(new MessagePageDto())));
        when(messageRepository.refreshHistory(anyString()))
                .thenReturn(Single.just(new Result.Success<>(new MessagePageDto())));
        when(messageRepository.sendRead(anyString(), anyLong())).thenReturn(Completable.complete());
        when(messageRepository.send(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Completable.complete());
        when(messageRepository.send(anyString(), any(), any(), anyString(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Completable.complete());
        when(messageRepository.retry(anyString())).thenReturn(Completable.complete());
        when(messageRepository.toggleReaction(anyString(), anyString(), anyString()))
                .thenReturn(Completable.complete());
        when(messageRepository.pinMessage(anyString(), anyString())).thenReturn(Completable.complete());
        when(messageRepository.unpinMessage(anyString(), anyString())).thenReturn(Completable.complete());
        when(messageRepository.isMine(nullable(String.class))).thenAnswer(invocation ->
                Single.just(Objects.equals("me-1", invocation.getArgument(0))));
        when(messageRepository.loadUntilLoaded(anyString(), anyString(), anyInt()))
                .thenReturn(Single.just(MessageJump.NotFound.INSTANCE));
        when(messageRepository.editMessage(anyString(), anyString(), anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        when(messageRepository.editMessage(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        when(messageRepository.deleteMessage(anyString(), anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        when(messageRepository.hideMessageForMe(anyString())).thenReturn(Completable.complete());
        when(tokenStore.observeRecallWindowConfig()).thenReturn(Flowable.just(RecallWindowConfig.DEFAULT));
        when(messageRepository.searchMessages(anyString(), anyString(), nullable(Long.class), anyInt()))
                .thenReturn(Single.just(new Result.Success<>(
                        new MessageSearchResult(List.of(), false, null))));
        when(conversationRepository.groupDetail(anyString())).thenReturn(Single.just(
                new Result.Success<>(new GroupDetail("Group", List.of(
                        new GroupMember("me-1", "Me", null, null, GroupMember.ROLE_ADMIN),
                        new GroupMember("u-2", "Lan", null, null, "MEMBER"))))));

        when(attachmentRepository.describe(any(Uri.class))).thenAnswer(invocation -> {
            Uri picked = invocation.getArgument(0);
            return Single.just(new LocalAttachment(picked, "pic.png", "image/png", 12L, "IMAGE"));
        });
        when(attachmentRepository.upload(any(Uri.class), nullable(String.class)))
                .thenReturn(Flowable.just(
                        new AttachmentUploadEvent.Progress(0.4f),
                        new AttachmentUploadEvent.Uploaded(ATTACHMENT)));
        when(attachmentRepository.downloadToCache(anyString(), nullable(String.class)))
                .thenReturn(Single.just(Result.failure(new ApiError.Network("down"))));
        when(conversationRepository.conversationFlow(anyString())).thenReturn(conversationFlow);
        when(session.getIsOpen()).thenReturn(isOpenFlow);
        when(session.sendTyping(anyString())).thenReturn(true);

        viewModel = newViewModel(new SavedStateHandle(Map.of("conversationId", CONVERSATION_ID)));
    }

    @After
    public void tearDown() {
        viewModelStore.clear();
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    private ChatViewModel newViewModel(SavedStateHandle handle) {
        ChatViewModel created = new ChatViewModel(
                handle, messageRepository, conversationRepository, attachmentRepository, session, tokenStore);
        viewModelStore.put("chat-" + (++viewModelCount), created);
        return created;
    }

    private ChatViewModel.UiState state() {
        return viewModel.getUiState().getValue();
    }

    private ChatSearchState searchState() {
        return viewModel.getSearchState().getValue();
    }

    @Test
    public void restSuccessHidesDisconnectedBannerWhileWebSocketIsDown() {
        isOpenFlow.onNext(false);
        assertFalse(state().isConnected());

        restReachabilityFlow.onNext(true);

        assertTrue(state().isConnected());
    }

    @Test
    public void networkFailureShowsDisconnectedBannerAfterRestWasReachable() {
        isOpenFlow.onNext(false);
        restReachabilityFlow.onNext(true);
        assertTrue(state().isConnected());

        restReachabilityFlow.onNext(false);

        assertFalse(state().isConnected());
    }

    @Test
    public void websocketReconnectHidesDisconnectedBannerEvenAfterRestFailure() {
        isOpenFlow.onNext(false);
        restReachabilityFlow.onNext(false);
        assertFalse(state().isConnected());

        isOpenFlow.onNext(true);

        assertTrue(state().isConnected());
    }

    private static ChatMessage message(
            String id, Long seq, boolean mine, DeliveryStatus status, String clientMessageId) {
        return new ChatMessage(
                id,
                CONVERSATION_ID,
                seq,
                mine ? "me-1" : "u-other",
                mine ? "Me" : "Lan",
                MessageDto.TYPE_TEXT,
                "hello " + id,
                null,
                null,
                null,
                1_700_000_000_000L,
                clientMessageId,
                status,
                mine,
                List.of(),
                List.of(),
                null,
                null);
    }

    private static ChatMessage message(String id, long seq, boolean mine) {
        return message(id, seq, mine, DeliveryStatus.SENT, null);
    }

    private static ChatMessage changed(
            ChatMessage source, String type, Long deletedAtEpochMs) {
        return new ChatMessage(
                source.getId(), source.getConversationId(), source.getSeq(), source.getSenderId(),
                source.getSenderDisplayName(), type, source.getContent(), source.getReplyToId(),
                source.getReplyToSenderName(), source.getReplyToSnippet(), source.getCreatedAtEpochMs(),
                source.getClientMessageId(), source.getStatus(), source.getMine(), source.getAttachments(),
                source.getReactions(), source.getEditedAtEpochMs(), deletedAtEpochMs);
    }

    private static ConversationSummary summary(
            String type, String role, String otherUserId, PeerPresence presence) {
        return new ConversationSummary(
                CONVERSATION_ID,
                type,
                "GROUP".equals(type) ? "Group" : null,
                null,
                null,
                null,
                0L,
                0,
                "GROUP".equals(type) ? null : "Peer",
                null,
                null,
                0L,
                role,
                otherUserId,
                false,
                presence);
    }

    private static void verifySend(
            MessageRepository repository,
            String conversationId,
            String content,
            String replyToId,
            String type,
            List<AttachmentDto> attachments,
            int count) {
        verify(repository, times(count)).send(
                eq(conversationId), eq(content), eq(replyToId), eq(type), eq(attachments));
    }

    @Test
    public void sendClearsInputAndDelegatesToOutboxPipeline() {
        viewModel.onInputChange("xin chào");
        assertEquals("xin chào", state().getInput());

        viewModel.send();

        assertEquals("", state().getInput());
        verifySend(messageRepository, CONVERSATION_ID, "xin chào", null,
                MessageDto.TYPE_TEXT, List.of(), 1);
    }

    @Test
    public void mentionDraftPersistsAndSendUsesFinalTrimmedOffsets() {
        String text = "Hi @Lan  ";
        MentionDraft draft = MentionDraft.empty(text).withMention(text, "u-2", 3, 4);
        viewModel.onComposerDraftChange(draft);
        assertEquals(draft.getMentions(), viewModel.getMentionDraft().getMentions());

        viewModel.send();

        verify(messageRepository).send(eq(CONVERSATION_ID), eq("Hi @Lan"), isNull(),
                eq(MessageDto.TYPE_TEXT), org.mockito.ArgumentMatchers.<List<AttachmentDto>>eq(List.of()),
                eq(List.of(new MessageMention("u-2", 3, 4))), eq(false));
        assertEquals(List.of(), viewModel.getMentionDraft().getMentions());
    }

    @Test
    public void groupMentionMembersAndMentionEditMetadataUseRepositoryPaths() {
        viewModel.loadGroupMembers();
        assertEquals(2, viewModel.getGroupMembers().getValue().size());
        MentionDraft draft = MentionDraft.empty("Hi @Lan").withMention("Hi @Lan", "u-2", 3, 4);
        viewModel.editMessage("m-mention", draft);
        verify(messageRepository).editMessage(CONVERSATION_ID, "m-mention", "Hi @Lan",
                List.of(new MessageMention("u-2", 3, 4)), false);
    }

    @Test
    public void blankInputNeverReachesRepository() {
        viewModel.onInputChange("   ");
        viewModel.send();
        verify(messageRepository, never()).send(anyString(), any(), any(), anyString(), any());
    }

    @Test
    public void optimisticPendingRowRendersAsSending() {
        ChatMessage pending = message("cm-1", null, true, DeliveryStatus.SENDING, "cm-1");
        messagesFlow.onNext(List.of(pending));

        assertEquals(1, state().getMessages().size());
        assertEquals(DeliveryStatus.SENDING, state().getMessages().get(0).getStatus());
        assertEquals(1, state().getSending().size());
    }

    @Test
    public void messageCreatedDedupShowsOneSentRowNotDuplicate() {
        ChatMessage pending = message("cm-1", null, true, DeliveryStatus.SENDING, "cm-1");
        ChatMessage acked = message("m-9", 9L, true, DeliveryStatus.SENT, "cm-1");
        messagesFlow.onNext(List.of(pending));
        assertEquals(DeliveryStatus.SENDING, state().getMessages().get(0).getStatus());

        // Repository merged the ack: pending row replaced by the stored one.
        messagesFlow.onNext(List.of(acked));

        assertEquals(1, state().getMessages().size());
        assertEquals("m-9", state().getMessages().get(0).getId());
        assertEquals(DeliveryStatus.SENT, state().getMessages().get(0).getStatus());
        assertTrue(state().getSending().isEmpty());
    }

    @Test
    public void tappingFailedMessageRequeuesIt() {
        viewModel.retryFailed("cm-7");
        verify(messageRepository).retry("cm-7");
    }

    @Test
    public void peerTypingShowsThenExpiresAfterTtl() {
        typingFlow.onNext(new TypingEvent(CONVERSATION_ID, "u-other", "Lan"));
        assertTrue(state().isPeerTyping());
        timerScheduler.advanceTimeBy(ChatViewModel.PEER_TYPING_TTL_MS + 1, TimeUnit.MILLISECONDS);
        assertFalse(state().isPeerTyping());
    }

    @Test
    public void typingEventForAnotherConversationIsIgnored() {
        typingFlow.onNext(new TypingEvent("other-conv", "u-other", "Lan"));
        assertFalse(state().isPeerTyping());
    }

    @Test
    public void ownTypingEchoDoesNotLightIndicator() {
        typingFlow.onNext(new TypingEvent(CONVERSATION_ID, "me-1", "Me"));
        assertFalse(state().isPeerTyping());
    }

    @Test
    public void inputChangesThrottleTypingSendsToOnePerInterval() {
        viewModel.onInputChange("a");
        viewModel.onInputChange("ab");
        viewModel.onInputChange("abc");
        verify(session, times(1)).sendTyping(CONVERSATION_ID);
    }

    @Test
    public void draftInputIsRestoredFromSavedStateHandle() {
        ChatViewModel restored = newViewModel(new SavedStateHandle(Map.of(
                "conversationId", CONVERSATION_ID, "chat_input", "nháp tay")));
        assertEquals("nháp tay", restored.getUiState().getValue().getInput());
    }

    @Test
    public void incomingMessageWhileOpenMarksItRead() {
        messagesFlow.onNext(List.of(message("m-1", 9L, false)));
        verify(messageRepository, times(1)).sendRead(CONVERSATION_ID, 9L);
    }

    @Test
    public void ownMessageDoesNotTriggerReadCall() {
        messagesFlow.onNext(List.of(message("m-2", 10L, true)));
        verify(messageRepository, never()).sendRead(CONVERSATION_ID, 10L);
    }

    @Test
    public void sendAttachmentsStagesPendingRowThenSendsWithAttachmentIds() {
        viewModel.sendAttachments(List.of(uri));

        verifySend(messageRepository, CONVERSATION_ID, "pic.png", null,
                "IMAGE", List.of(ATTACHMENT), 1);
        assertTrue(state().getPendingAttachments().isEmpty());
    }

    @Test
    public void recordedVoiceDurationIsPassedThroughTheUpload() {
        long durationMs = 3_200L;
        AttachmentDto audio = new AttachmentDto(
                "att-audio", null, "voice.m4a", "audio/mp4", 23_730L,
                null, null, durationMs, "/api/v1/files/voice.m4a");
        when(attachmentRepository.describe(uri)).thenReturn(Single.just(
                new LocalAttachment(uri, "voice.m4a", "audio/mp4", 23_730L, "AUDIO")));
        when(attachmentRepository.upload(uri, "audio/mp4", durationMs)).thenReturn(Flowable.just(
                new AttachmentUploadEvent.Uploaded(audio)));

        viewModel.sendAttachments(List.of(uri), durationMs);

        verify(attachmentRepository).upload(uri, "audio/mp4", durationMs);
        verifySend(messageRepository, CONVERSATION_ID, "voice.m4a", null,
                "AUDIO", List.of(audio), 1);
    }

    @Test
    public void serverSniffedVideoMimeWinsWhenPickerCannotIdentifyUri() {
        AttachmentDto video = new AttachmentDto("att-video", "clip.mp4", "video/mp4", 4L);
        when(attachmentRepository.describe(uri)).thenReturn(Single.just(
                new LocalAttachment(uri, "clip.mp4", null, 4L, "FILE")));
        when(attachmentRepository.upload(uri, null)).thenReturn(Flowable.just(
                new AttachmentUploadEvent.Uploaded(video)));

        viewModel.sendAttachments(List.of(uri));

        verifySend(messageRepository, CONVERSATION_ID, "clip.mp4", null,
                "VIDEO", List.of(video), 1);
    }

    @Test
    public void inFlightUploadShowsPendingRowWithProgress() {
        when(attachmentRepository.upload(any(Uri.class), nullable(String.class)))
                .thenReturn(Flowable.concat(
                        Flowable.just(new AttachmentUploadEvent.Progress(0.3f)),
                        Flowable.never()));

        viewModel.sendAttachments(List.of(uri));

        PendingAttachment row = state().getPendingAttachments().get(0);
        assertEquals(PendingAttachment.PendingState.UPLOADING, row.getState());
        assertEquals(0.3f, row.getProgress(), 0.0001f);
        assertEquals("pic.png", row.getDisplayName());
        verify(messageRepository, never()).send(anyString(), any(), any(), anyString(), any());
    }

    @Test
    public void failedUploadMarksRowFailedAndNeverSends() {
        when(attachmentRepository.upload(any(Uri.class), nullable(String.class)))
                .thenReturn(Flowable.just(
                        new AttachmentUploadEvent.Progress(0.2f),
                        new AttachmentUploadEvent.Failed(new ApiError.Network("down"))));

        viewModel.sendAttachments(List.of(uri));

        assertEquals(PendingAttachment.PendingState.FAILED,
                state().getPendingAttachments().get(0).getState());
        verify(messageRepository, never()).send(anyString(), any(), any(), anyString(), any());
    }

    @Test
    public void removeXDropsPendingRowAndCancelsUpload() {
        when(attachmentRepository.upload(any(Uri.class), nullable(String.class)))
                .thenReturn(Flowable.concat(
                        Flowable.just(new AttachmentUploadEvent.Progress(0.1f)),
                        Flowable.never()));
        viewModel.sendAttachments(List.of(uri));
        String localId = state().getPendingAttachments().get(0).getLocalId();

        viewModel.removePendingAttachment(localId);

        assertTrue(state().getPendingAttachments().isEmpty());
    }

    @Test
    public void replyDraftPassesReplyToIdIntoSendThenClears() {
        viewModel.setReplyTarget(message("m-5", 5L, false));
        assertEquals("m-5", state().getReplyTarget().getMessageId());
        viewModel.onInputChange("đồng ý");
        viewModel.send();

        verifySend(messageRepository, CONVERSATION_ID, "đồng ý", "m-5",
                MessageDto.TYPE_TEXT, List.of(), 1);
        assertNull(state().getReplyTarget());
    }

    @Test
    public void acknowledgedMessagesKeepTheirExistingActions() {
        for (DeliveryStatus status : List.of(DeliveryStatus.DELIVERED, DeliveryStatus.READ)) {
            ChatMessage target = message("acked-" + status, 8L, true, status, null);
            viewModel.setReplyTarget(target);
            assertEquals(target.getId(), state().getReplyTarget().getMessageId());
            assertTrue(viewModel.canEdit(target));
            assertTrue(viewModel.canDelete(target));
            viewModel.toggleReaction(target, "👍");
            verify(messageRepository).toggleReaction(target.getId(), CONVERSATION_ID, "👍");
            viewModel.togglePin(target);
            verify(messageRepository).pinMessage(CONVERSATION_ID, target.getId());
        }
    }

    @Test
    public void pendingMessagesCannotBecomeReplyTarget() {
        viewModel.setReplyTarget(message("cm-1", null, true, DeliveryStatus.SENDING, null));
        assertNull(state().getReplyTarget());
    }

    @Test
    public void cancelClearsReplyDraftWithoutSending() {
        viewModel.setReplyTarget(message("m-5", 5L, false));
        viewModel.clearReplyTarget();
        assertNull(state().getReplyTarget());
        verify(messageRepository, never()).send(anyString(), any(), any(), anyString(), any());
    }

    @Test
    public void togglePinPinsWhenUnpinnedAndUnpinsWhenPinned() {
        ChatMessage target = message("m-1", 1L, false);
        viewModel.togglePin(target);
        verify(messageRepository).pinMessage(CONVERSATION_ID, "m-1");

        pinsFlow.onNext(List.of(target));
        viewModel.togglePin(target);
        verify(messageRepository).unpinMessage(CONVERSATION_ID, "m-1");
    }

    @Test
    public void toggleReactionDelegatesEmojiToggleToRepository() {
        viewModel.toggleReaction(message("m-3", 3L, false), "👍");
        verify(messageRepository).toggleReaction("m-3", CONVERSATION_ID, "👍");
    }

    @Test
    public void pinFailuresSurfaceInUiErrorState() {
        interactionFlow.onNext(new ApiError.Unknown("PIN_LIMIT"));
        assertEquals(new ApiError.Unknown("PIN_LIMIT"), state().getError());
    }

    @Test
    public void jumpToMessageEmitsScrollTargetOnceLoaded() {
        when(messageRepository.loadUntilLoaded(CONVERSATION_ID, "m-old", MessageRepository.MAX_JUMP_PAGES))
                .thenReturn(Single.just(MessageJump.Found.INSTANCE));
        AtomicReference<ChatSingleLiveEvent.Event<String>> event = new AtomicReference<>();
        androidx.lifecycle.Observer<ChatSingleLiveEvent.Event<String>> observer = event::set;
        viewModel.getJumpTargets().observeForever(observer);

        viewModel.jumpToMessage("m-old");

        assertEquals("m-old", event.get().consume());
        viewModel.getJumpTargets().removeObserver(observer);
    }

    @Test
    public void jumpToMessageReportsGoneTarget() {
        when(messageRepository.loadUntilLoaded(anyString(), anyString(), anyInt()))
                .thenReturn(Single.just(MessageJump.NotFound.INSTANCE));
        viewModel.jumpToMessage("m-gone");
        assertEquals(new ApiError.Unknown(ChatViewModel.ERROR_MESSAGE_GONE), state().getError());
    }

    @Test
    public void jumpToMessageSurfacesHistoryPageFailure() {
        when(messageRepository.loadUntilLoaded(anyString(), anyString(), anyInt()))
                .thenReturn(Single.just(new MessageJump.Failed(new ApiError.Network("down"))));
        viewModel.jumpToMessage("m-old");
        assertEquals(new ApiError.Network("down"), state().getError());
    }

    @Test
    public void canEditAllowsOnlyOwnLiveDeliveredTextMessages() {
        assertTrue(viewModel.canEdit(message("m-1", 1L, true)));
        assertFalse(viewModel.canEdit(message("m-2", 2L, false)));
        assertFalse(viewModel.canEdit(message("m-3", 3L, true, DeliveryStatus.SENDING, null)));
        assertFalse(viewModel.canEdit(changed(message("m-4", 4L, true), "TEXT", 1L)));
        assertFalse(viewModel.canEdit(changed(message("m-5", 5L, true), "IMAGE", null)));
    }

    @Test
    public void canDeleteAllowsOwnLiveMessagesOfAnyType() {
        assertTrue(viewModel.canDelete(message("m-1", 1L, true)));
        assertTrue(viewModel.canDelete(changed(message("m-2", 2L, true), "IMAGE", null)));
        assertFalse(viewModel.canDelete(message("m-3", 3L, false)));
        assertFalse(viewModel.canDelete(changed(message("m-4", 4L, true), "TEXT", 1L)));
    }

    @Test
    public void editMessageTrimsAndDelegates() {
        viewModel.editMessage("m-1", "  new text  ");
        verify(messageRepository).editMessage(CONVERSATION_ID, "m-1", "new text");
    }

    @Test
    public void editMessageWithBlankInputNeverCallsRepository() {
        viewModel.editMessage("m-1", "   ");
        verify(messageRepository, never()).editMessage(anyString(), anyString(), anyString());
    }

    @Test
    public void editFailureSurfacesInUiErrorState() {
        when(messageRepository.editMessage(anyString(), anyString(), anyString()))
                .thenReturn(Single.just(Result.failure(new ApiError.Unknown("MESSAGE_DELETED"))));
        viewModel.editMessage("m-1", "new");
        assertEquals(new ApiError.Unknown("MESSAGE_DELETED"), state().getError());
    }

    @Test
    public void editSuccessEmitsMessageEditedAndFailureDoesNot() {
        AtomicReference<ChatSingleLiveEvent.Event<ChatActionEvent>> event = new AtomicReference<>();
        AtomicInteger eventCount = new AtomicInteger();
        androidx.lifecycle.Observer<ChatSingleLiveEvent.Event<ChatActionEvent>> observer = value -> {
            event.set(value);
            eventCount.incrementAndGet();
        };
        viewModel.getActionEvents().observeForever(observer);
        viewModel.editMessage("m-1", "updated");
        assertEquals(new ChatActionEvent.MessageEdited("m-1"), event.get().consume());

        when(messageRepository.editMessage(anyString(), anyString(), anyString()))
                .thenReturn(Single.just(Result.failure(new ApiError.Unknown("NETWORK"))));
        viewModel.editMessage("m-1", "again");
        assertEquals(1, eventCount.get());
        viewModel.getActionEvents().removeObserver(observer);
    }

    @Test
    public void deleteMessageDelegatesAndSurfacesFailures() {
        viewModel.deleteMessage("m-1");
        verify(messageRepository).deleteMessage(CONVERSATION_ID, "m-1");
        when(messageRepository.deleteMessage(anyString(), anyString()))
                .thenReturn(Single.just(Result.failure(new ApiError.Unknown("NOT_MESSAGE_AUTHOR"))));
        viewModel.deleteMessage("m-2");
        assertEquals(new ApiError.Unknown("NOT_MESSAGE_AUTHOR"), state().getError());
    }

    @Test
    public void expiredRecallShowsLocalizedErrorAndRefreshesHistory() {
        when(messageRepository.deleteMessage(anyString(), anyString()))
                .thenReturn(Single.just(Result.failure(new ApiError.Unknown("RECALL_WINDOW_EXPIRED"))));

        viewModel.deleteMessage("m-expired");

        assertEquals(new ApiError.Unknown(ChatViewModel.ERROR_RECALL_WINDOW_EXPIRED), state().getError());
        verify(messageRepository).refreshHistory(CONVERSATION_ID);
    }

    @Test
    public void deleteForMePersistsLocallyWithoutCallingServer() {
        viewModel.deleteMessageForMe("m-expired");

        verify(messageRepository).hideMessageForMe("m-expired");
        verify(messageRepository, Mockito.never()).deleteMessage(anyString(), anyString());
    }

    @Test
    public void forwardResendsContentAsNewTextAndEmitsEvent() {
        messagesFlow.onNext(List.of(message("m-1", 1L, true)));
        AtomicReference<ChatSingleLiveEvent.Event<ChatActionEvent>> event = new AtomicReference<>();
        androidx.lifecycle.Observer<ChatSingleLiveEvent.Event<ChatActionEvent>> observer = event::set;
        viewModel.getActionEvents().observeForever(observer);

        viewModel.forwardMessage("m-1", TARGET_CONVERSATION_ID);

        assertEquals(new ChatActionEvent.MessageForwarded(TARGET_CONVERSATION_ID), event.get().consume());
        verifySend(messageRepository, TARGET_CONVERSATION_ID, "hello m-1", null,
                MessageDto.TYPE_TEXT, List.of(), 1);
        viewModel.getActionEvents().removeObserver(observer);
    }

    @Test
    public void forwardSkipsDeletedPendingAndNonTextMessages() {
        messagesFlow.onNext(List.of(
                changed(message("m-del", 1L, true), MessageDto.TYPE_TEXT, 1L),
                message("cm-1", null, true, DeliveryStatus.SENDING, "cm-1"),
                changed(message("m-image", 2L, true), MessageDto.TYPE_IMAGE, null),
                changed(message("m-audio", 3L, true), MessageDto.TYPE_AUDIO, null),
                changed(message("m-video", 4L, true), MessageDto.TYPE_VIDEO, null),
                changed(message("m-file", 5L, true), MessageDto.TYPE_FILE, null)));
        viewModel.forwardMessage("m-del", TARGET_CONVERSATION_ID);
        viewModel.forwardMessage("cm-1", TARGET_CONVERSATION_ID);
        viewModel.forwardMessage("m-image", TARGET_CONVERSATION_ID);
        viewModel.forwardMessage("m-audio", TARGET_CONVERSATION_ID);
        viewModel.forwardMessage("m-video", TARGET_CONVERSATION_ID);
        viewModel.forwardMessage("m-file", TARGET_CONVERSATION_ID);
        viewModel.forwardMessage("m-not-loaded", TARGET_CONVERSATION_ID);
        verify(messageRepository, never()).send(anyString(), any(), any(), anyString(), any());
    }

    @Test
    public void searchDebouncesKeystrokesAndQueriesFinalTermAfter300Ms() {
        viewModel.search("h");
        viewModel.search("hi");
        timerScheduler.advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS - 1, TimeUnit.MILLISECONDS);
        verify(messageRepository, never()).searchMessages(
                anyString(), anyString(), nullable(Long.class), anyInt());

        timerScheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS);

        verify(messageRepository).searchMessages(
                CONVERSATION_ID, "hi", null, ChatViewModel.SEARCH_PAGE_SIZE);
    }

    @Test
    public void searchBelowTwoCharactersClearsResultsWithoutCall() {
        when(messageRepository.searchMessages(anyString(), anyString(), nullable(Long.class), anyInt()))
                .thenReturn(Single.just(new Result.Success<>(
                        new MessageSearchResult(List.of(), false, null))));
        viewModel.search("hello");
        timerScheduler.advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS + 1, TimeUnit.MILLISECONDS);
        viewModel.search("h");
        timerScheduler.advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS + 1, TimeUnit.MILLISECONDS);

        assertEquals("h", searchState().getQuery());
        assertTrue(searchState().getResults().isEmpty());
        assertFalse(searchState().isLoading());
        verify(messageRepository, times(1)).searchMessages(
                anyString(), anyString(), nullable(Long.class), anyInt());
    }

    @Test
    public void searchExposesResultsAndPagesWithServerCursor() {
        List<ChatMessage> first = List.of(message("m-9", 9L, true), message("m-8", 8L, false));
        List<ChatMessage> second = List.of(message("m-7", 7L, true));
        when(messageRepository.searchMessages(CONVERSATION_ID, "hi", null,
                ChatViewModel.SEARCH_PAGE_SIZE))
                .thenReturn(Single.just(new Result.Success<>(
                        new MessageSearchResult(first, true, 8L))));
        when(messageRepository.searchMessages(CONVERSATION_ID, "hi", 8L,
                ChatViewModel.SEARCH_PAGE_SIZE))
                .thenReturn(Single.just(new Result.Success<>(
                        new MessageSearchResult(second, false, null))));

        viewModel.search("hi");
        timerScheduler.advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS + 1, TimeUnit.MILLISECONDS);
        assertEquals(List.of("m-9", "m-8"), ids(searchState().getResults()));
        assertTrue(searchState().getHasMore());

        viewModel.loadMoreSearch();

        assertEquals(List.of("m-9", "m-8", "m-7"), ids(searchState().getResults()));
        assertFalse(searchState().getHasMore());
    }

    private static List<String> ids(List<ChatMessage> messages) {
        List<String> ids = new ArrayList<>();
        for (ChatMessage message : messages) {
            ids.add(message.getId());
        }
        return ids;
    }

    @Test
    public void clearSearchDropsQueryAndResults() {
        when(messageRepository.searchMessages(anyString(), anyString(), nullable(Long.class), anyInt()))
                .thenReturn(Single.just(new Result.Success<>(new MessageSearchResult(
                        List.of(message("m-1", 1L, true)), false, null))));
        viewModel.search("hi");
        timerScheduler.advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS + 1, TimeUnit.MILLISECONDS);
        assertFalse(searchState().getResults().isEmpty());

        viewModel.clearSearch();

        assertEquals(new ChatSearchState(), searchState());
    }

    @Test
    public void searchFailureSurfacesOnSearchStateOnly() {
        when(messageRepository.searchMessages(anyString(), anyString(), nullable(Long.class), anyInt()))
                .thenReturn(Single.just(Result.failure(new ApiError.Network("down"))));
        viewModel.search("hi");
        timerScheduler.advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS + 1, TimeUnit.MILLISECONDS);

        assertEquals(new ApiError.Network("down"), searchState().getError());
        assertFalse(searchState().isLoading());
        assertNull(state().getError());
    }

    @Test
    public void peerPresenceComesFromDirectConversationSummary() {
        PeerPresence presence = new PeerPresence(true, 1_700_000_000_000L);
        conversationFlow.onNext(Optional.of(summary("DIRECT", null, null, presence)));
        assertEquals(true, state().getPeerPresence().isOnline());
    }

    @Test
    public void directPeerIdIsExposedBeforeFirstMessage() {
        conversationFlow.onNext(Optional.of(summary("DIRECT", null, "u-peer", null)));
        assertTrue(messagesFlow.getValue().isEmpty());
        assertEquals("u-peer", state().getOtherUserId());
    }

    @Test
    public void canPinGatesPinAffordanceToGroupAdminsAndDirectMembers() {
        // A non-admin member must not be offered a doomed pin (server 403s it).
        conversationFlow.onNext(Optional.of(summary("GROUP", "MEMBER", null, null)));
        assertEquals(false, state().isCanPin());

        conversationFlow.onNext(Optional.of(summary("GROUP", "ADMIN", null, null)));
        assertEquals(true, state().isCanPin());

        // Unknown role (legacy payload without members[]) stays hidden.
        conversationFlow.onNext(Optional.of(summary("GROUP", null, null, null)));
        assertEquals(false, state().isCanPin());

        // DIRECT members always pin.
        conversationFlow.onNext(Optional.of(summary("DIRECT", null, null, null)));
        assertEquals(true, state().isCanPin());
    }

    @Test
    public void consumedActionEventDoesNotReplayAfterObserverReattaches() {
        when(messageRepository.editMessage(anyString(), anyString(), anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        AtomicReference<ChatSingleLiveEvent.Event<ChatActionEvent>> first = new AtomicReference<>();
        androidx.lifecycle.Observer<ChatSingleLiveEvent.Event<ChatActionEvent>> firstObserver = first::set;
        viewModel.getActionEvents().observeForever(firstObserver);
        viewModel.editMessage("m-1", "updated");
        assertEquals(new ChatActionEvent.MessageEdited("m-1"), first.get().consume());
        viewModel.getActionEvents().removeObserver(firstObserver);

        AtomicReference<ChatSingleLiveEvent.Event<ChatActionEvent>> afterRotation = new AtomicReference<>();
        androidx.lifecycle.Observer<ChatSingleLiveEvent.Event<ChatActionEvent>> secondObserver = afterRotation::set;
        viewModel.getActionEvents().observeForever(secondObserver);
        assertNull(afterRotation.get().consume());
        viewModel.getActionEvents().removeObserver(secondObserver);
    }

    @Test
    public void toggleConversationPinDelegatesToRepositoryAndSurfacesCapError() {
        assertFalse(state().isConversationPinned());

        ApiError capError = new ApiError.Unknown("CONVERSATION_PIN_LIMIT");
        when(conversationRepository.setConversationPinned(CONVERSATION_ID, true))
                .thenReturn(Single.just(Result.failure(capError)));

        viewModel.toggleConversationPin();

        verify(conversationRepository, times(1)).setConversationPinned(CONVERSATION_ID, true);
        assertEquals(capError, state().getError());
    }

    @Test
    public void toggleConversationPinTogglesStateWhenAlreadyPinned() {
        conversationFlow.onNext(Optional.of(new ConversationSummary(
                CONVERSATION_ID, "DIRECT", null, "last", "Lan", 1_700_000_000_000L,
                42L, 0, "Lan", null, null, 0L, 0L, null, null, false, null,
                true, 1_700_000_000_000L)));

        assertTrue(state().isConversationPinned());

        when(conversationRepository.setConversationPinned(CONVERSATION_ID, false))
                .thenReturn(Single.just(new Result.Success<Void>(null)));

        viewModel.toggleConversationPin();

        verify(conversationRepository, times(1)).setConversationPinned(CONVERSATION_ID, false);
    }

    @Test
    public void aiOptOutLoadFailureShowsAnInlineError() {
        ApiError failure = new ApiError.Network("down");
        when(conversationRepository.getAiOptOut(CONVERSATION_ID))
                .thenReturn(Single.just(Result.failure(failure)));

        viewModel.loadAiOptOut();

        ChatViewModel.AiOptOutState state = viewModel.getAiOptOutState().getValue();
        assertFalse(state.isLoading());
        assertEquals(failure, state.getError());
    }

    @Test
    public void aiOptOutSaveFailureRevertsTheOptimisticSwitch() {
        ApiError failure = new ApiError.Network("down");
        when(conversationRepository.setAiOptOut(CONVERSATION_ID, true))
                .thenReturn(Single.just(Result.failure(failure)));

        viewModel.setAiOptOut(true);

        ChatViewModel.AiOptOutState state = viewModel.getAiOptOutState().getValue();
        assertFalse("failed save must revert the switch", state.getOptOut());
        assertFalse(state.isSaving());
        assertEquals(failure, state.getError());
    }

    @Test
    public void aiOptOutSaveSuccessTakesTheServerState() {
        when(conversationRepository.setAiOptOut(CONVERSATION_ID, true))
                .thenReturn(Single.just(new Result.Success<>(Boolean.TRUE)));

        viewModel.setAiOptOut(true);

        ChatViewModel.AiOptOutState state = viewModel.getAiOptOutState().getValue();
        assertTrue(state.getOptOut());
        assertFalse(state.isSaving());
        assertNull(state.getError());
    }

    @Test
    public void successfulMuteEmitsAOneShotConfirmation() {
        when(conversationRepository.muteConversationForever(CONVERSATION_ID))
                .thenReturn(Single.just(new Result.Success<Void>(null)));

        viewModel.muteConversation(null);

        ChatSingleLiveEvent.Event<ChatActionEvent> event = viewModel.getActionEvents().getValue();
        assertEquals(new ChatActionEvent.PreferenceChanged(
                        ChatActionEvent.PreferenceChanged.Kind.MUTED),
                event.consume());
    }
}
