package com.nexachat.feature.chat;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.crypto.KeysetTrustStore;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.MessagePageDto;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.common.Result;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.AttachmentRepository;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.data.MessageRepository;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.mockito.Mockito;

/** Exact wire output of {@link ChatViewModel#sendSharedContent}. */
public final class ChatSharedContentSendTest {

    private static final String CONVERSATION_ID = "c-1";

    @Rule
    public final TestRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private MessageRepository messageRepository;
    private ConversationRepository conversationRepository;
    private AttachmentRepository attachmentRepository;
    private RealtimeSession session;
    private TokenStore tokenStore;
    private PublishProcessor<Optional<ConversationSummary>> conversationFlow;
    private ChatViewModel viewModel;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        messageRepository = Mockito.mock(MessageRepository.class);
        conversationRepository = Mockito.mock(ConversationRepository.class);
        attachmentRepository = Mockito.mock(AttachmentRepository.class);
        session = Mockito.mock(RealtimeSession.class);
        tokenStore = Mockito.mock(TokenStore.class);
        conversationFlow = PublishProcessor.create();

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
        when(messageRepository.keysetPauseCode(anyString(), anyString()))
                .thenReturn(Single.just(Optional.empty()));
        when(tokenStore.observeRecallWindowConfig()).thenReturn(Flowable.never());
        when(conversationRepository.conversationFlow(anyString())).thenReturn(conversationFlow);
        when(session.getIsOpen()).thenReturn(Flowable.never());

        viewModel = newViewModel(null);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    private ChatViewModel newViewModel(KeysetTrustStore trustStore) {
        return trustStore == null
                ? new ChatViewModel(
                        new SavedStateHandle(Map.of("conversationId", CONVERSATION_ID)),
                        messageRepository, conversationRepository, attachmentRepository,
                        session, tokenStore)
                : new ChatViewModel(
                        new SavedStateHandle(Map.of("conversationId", CONVERSATION_ID)),
                        messageRepository, conversationRepository, attachmentRepository,
                        session, tokenStore, trustStore);
    }

    @Test
    public void locationSendsAsTextToken() {
        viewModel.sendSharedContent(new SharedContent.Location(21.0278, 105.8342, "Home"));

        verify(messageRepository).send(
                eq(CONVERSATION_ID),
                eq("nexachat:loc:v1 21.0278,105.8342 Home"),
                eq(null),
                eq(MessageDto.TYPE_TEXT),
                eq(List.of()));
    }

    @Test
    public void locationWithoutLabelSendsBareCoordinates() {
        viewModel.sendSharedContent(new SharedContent.Location(-33.8568, 151.2153, null));

        verify(messageRepository).send(
                eq(CONVERSATION_ID),
                eq("nexachat:loc:v1 -33.8568,151.2153"),
                eq(null),
                eq(MessageDto.TYPE_TEXT),
                eq(List.of()));
    }

    @Test
    public void contactSendsAsThreeLineToken() {
        viewModel.sendSharedContent(new SharedContent.Contact("An", "+84901234567"));

        verify(messageRepository).send(
                eq(CONVERSATION_ID),
                eq("nexachat:contact:v1\nAn\n+84901234567"),
                eq(null),
                eq(MessageDto.TYPE_TEXT),
                eq(List.of()));
    }

    @Test
    public void sharedSendConsumesThePendingReplyTarget() {
        viewModel.setReplyTarget(textMessage("reply-a"));
        viewModel.sendSharedContent(new SharedContent.Contact("An", "+84901234567"));

        verify(messageRepository).send(
                eq(CONVERSATION_ID),
                eq("nexachat:contact:v1\nAn\n+84901234567"),
                eq("reply-a"),
                eq(MessageDto.TYPE_TEXT),
                eq(List.of()));
        assertNull(viewModel.getUiState().getValue().getReplyTarget());
    }

    @Test
    public void keysetPauseRefusesSharedSendLikeTextSend() {
        KeysetTrustStore trustStore = Mockito.mock(KeysetTrustStore.class);
        when(messageRepository.currentUserId()).thenReturn(Maybe.just("me-1"));
        when(trustStore.state("me-1", CONVERSATION_ID)).thenReturn(
                Single.just(Optional.of(new KeysetTrustStore.Record(
                        KeysetTrustStore.TrustState.CHANGED, "00", new byte[] {1}, null, 1L))));
        ChatViewModel paused = newViewModel(trustStore);
        conversationFlow.onNext(Optional.of(e2eeSummary(true)));

        paused.sendSharedContent(new SharedContent.Contact("An", "+84901234567"));

        verify(messageRepository, never()).send(
                eq(CONVERSATION_ID), eq("nexachat:contact:v1\nAn\n+84901234567"),
                any(), anyString(), any());
    }

    private static ConversationSummary e2eeSummary(boolean e2eeEnabled) {
        return new ConversationSummary(
                CONVERSATION_ID, "DIRECT", null, null, null, null,
                0L, 0, 0L, "Peer", null, null, 0L, 0L, null, "peer-1",
                false, null, false, null, false, false, false, null,
                List.of(), e2eeEnabled, false, null);
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
