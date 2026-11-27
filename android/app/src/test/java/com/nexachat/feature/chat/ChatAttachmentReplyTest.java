package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Uri;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.MessagePageDto;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.common.Result;
import com.nexachat.data.AttachmentRepository;
import com.nexachat.data.AttachmentSendTicket;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.data.MessageRepository;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.mockito.Mockito;

public final class ChatAttachmentReplyTest {

    private static final String CONVERSATION_ID = "c-1";

    @Rule
    public final TestRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private MessageRepository messageRepository;
    private AttachmentRepository attachmentRepository;
    private ChatViewModel viewModel;
    private Uri uri;
    private CompletableSubject heldStage;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        messageRepository = Mockito.mock(MessageRepository.class);
        ConversationRepository conversationRepository = Mockito.mock(ConversationRepository.class);
        attachmentRepository = Mockito.mock(AttachmentRepository.class);
        RealtimeSession session = Mockito.mock(RealtimeSession.class);
        uri = Mockito.mock(Uri.class);
        heldStage = CompletableSubject.create();

        when(messageRepository.messagesFlow(anyString())).thenReturn(Flowable.never());
        when(messageRepository.getTypingEvents()).thenReturn(Flowable.never());
        when(messageRepository.pinsFlow(anyString())).thenReturn(Flowable.never());
        when(messageRepository.getInteractionEvents()).thenReturn(Flowable.never());
        when(messageRepository.getRestReachability()).thenReturn(Flowable.never());
        when(messageRepository.openConversation(anyString())).thenReturn(Completable.complete());
        when(messageRepository.refreshHistory(anyString(), nullable(Long.class)))
                .thenReturn(Single.just(new Result.Success<>(new MessagePageDto())));
        when(messageRepository.refreshHistory(anyString()))
                .thenReturn(Single.just(new Result.Success<>(new MessagePageDto())));
        when(messageRepository.send(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Completable.complete());
        when(conversationRepository.conversationFlow(anyString())).thenReturn(Flowable.never());
        when(session.getIsOpen()).thenReturn(Flowable.never());
        when(messageRepository.queueAttachment(anyString(), any(), nullable(Long.class),
                nullable(String.class), any())).thenReturn(heldStage);

        viewModel = new ChatViewModel(
                new SavedStateHandle(Map.of("conversationId", CONVERSATION_ID)),
                messageRepository,
                conversationRepository,
                attachmentRepository,
                session);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test
    public void attachmentBatchQuotesTheReplyCapturedAtStartWhenTheTargetChanges() {
        viewModel.setReplyTarget(textMessage("reply-a"));
        viewModel.sendAttachments(List.of(uri));
        viewModel.setReplyTarget(textMessage("reply-b"));

        heldStage.onComplete();

        verify(messageRepository).queueAttachment(eq(CONVERSATION_ID), eq(uri),
                eq(null), eq("reply-a"), any(AttachmentSendTicket.class));
        assertEquals("reply-b", viewModel.getUiState().getValue().getReplyTarget().getMessageId());

        viewModel.onInputChange("next");
        viewModel.send();
        verify(messageRepository).send(
                eq(CONVERSATION_ID), eq("next"), eq("reply-b"),
                eq(MessageDto.TYPE_TEXT), eq(List.of()));
    }

    @Test
    public void voiceBatchQuotesTheReplyCapturedAtStartWhenTheTargetChanges() {
        viewModel.setReplyTarget(textMessage("reply-a"));
        viewModel.sendAttachments(List.of(uri), 2_000L);
        viewModel.setReplyTarget(textMessage("reply-b"));
        heldStage.onComplete();

        verify(messageRepository).queueAttachment(eq(CONVERSATION_ID), eq(uri),
                eq(2_000L), eq("reply-a"), any(AttachmentSendTicket.class));
        assertEquals("reply-b", viewModel.getUiState().getValue().getReplyTarget().getMessageId());
    }

    @Test
    public void finishedAttachmentClearsOnlyTheReplyItQuoted() {
        heldStage.onComplete();

        viewModel.setReplyTarget(textMessage("reply-a"));
        viewModel.sendAttachments(List.of(uri));

        verify(messageRepository).queueAttachment(eq(CONVERSATION_ID), eq(uri),
                eq(null), eq("reply-a"), any(AttachmentSendTicket.class));
        assertNull(viewModel.getUiState().getValue().getReplyTarget());

        viewModel.onInputChange("later");
        viewModel.send();
        verify(messageRepository, times(1)).send(
                eq(CONVERSATION_ID), eq("later"), eq(null),
                eq(MessageDto.TYPE_TEXT), eq(List.of()));
    }

    private static ChatMessage textMessage(String id) {
        return new ChatMessage(
                id,
                CONVERSATION_ID,
                5L,
                "u-other",
                "Lan",
                MessageDto.TYPE_TEXT,
                "hello " + id,
                null,
                null,
                null,
                1_700_000_000_000L,
                null,
                DeliveryStatus.SENT,
                false,
                List.of(),
                List.of(),
                null,
                null);
    }
}
