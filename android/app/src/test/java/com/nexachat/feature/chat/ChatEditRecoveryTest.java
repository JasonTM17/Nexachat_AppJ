package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.MessagePageDto;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.data.AttachmentRepository;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.data.MessageRepository;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.mockito.Mockito;

public final class ChatEditRecoveryTest {

    private static final String CONVERSATION_ID = "c-1";

    @Rule
    public final TestRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private MessageRepository messageRepository;
    private ConversationRepository conversationRepository;
    private AttachmentRepository attachmentRepository;
    private RealtimeSession session;
    private SavedStateHandle handle;
    private ChatViewModel viewModel;
    private SingleSubject<Result<Unit>> editResult;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        messageRepository = Mockito.mock(MessageRepository.class);
        conversationRepository = Mockito.mock(ConversationRepository.class);
        attachmentRepository = Mockito.mock(AttachmentRepository.class);
        session = Mockito.mock(RealtimeSession.class);
        editResult = SingleSubject.create();
        handle = new SavedStateHandle(Map.of("conversationId", CONVERSATION_ID));

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
        when(messageRepository.editMessage(anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(editResult);
        when(conversationRepository.conversationFlow(anyString())).thenReturn(Flowable.never());
        when(session.getIsOpen()).thenReturn(Flowable.never());

        viewModel = newModel();
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test
    public void heldEditSurvivesRotationAndFailureUntilSuccessClearsOnce() {
        viewModel.onInputChange("ordinary draft");
        viewModel.beginEdit(ownText("m-edit", "hello original"));
        viewModel.onEditDraftChange(MentionDraft.empty("rewritten body"));
        viewModel.submitEdit();

        assertEdit("m-edit", "hello original", "rewritten body", viewModel);
        assertEquals("ordinary draft", viewModel.getUiState().getValue().getInput());

        ChatViewModel rotated = newModel();
        assertEdit("m-edit", "hello original", "rewritten body", rotated);
        assertEquals("ordinary draft", rotated.getUiState().getValue().getInput());

        editResult.onSuccess(Result.failure(new ApiError.Unknown("EDIT_FAILED")));
        assertEdit("m-edit", "hello original", "rewritten body", viewModel);
        assertEquals(new ApiError.Unknown("EDIT_FAILED"), viewModel.getUiState().getValue().getError());
        assertEdit("m-edit", "hello original", "rewritten body", rotated);

        when(messageRepository.editMessage(anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        rotated.submitEdit();
        assertNull(rotated.currentEditSession());
        assertEquals("ordinary draft", rotated.getUiState().getValue().getInput());

        rotated.submitEdit();
        assertNull(rotated.currentEditSession());
        assertEquals("ordinary draft", rotated.getUiState().getValue().getInput());
        verify(messageRepository, times(2)).editMessage(
                eq(CONVERSATION_ID), eq("m-edit"), eq("rewritten body"), eq(List.of()), eq(false));
    }

    @Test
    public void cancelEditRestoresTheOrdinaryDraft() {
        viewModel.onInputChange("ordinary draft");
        viewModel.beginEdit(ownText("m-edit", "hello original"));
        viewModel.onEditDraftChange(MentionDraft.empty("temporary"));

        viewModel.cancelEdit();

        assertNull(viewModel.currentEditSession());
        assertEquals("ordinary draft", viewModel.getUiState().getValue().getInput());
        verify(messageRepository, never()).editMessage(
                anyString(), anyString(), anyString(), any(), anyBoolean());
    }

    private ChatViewModel newModel() {
        return new ChatViewModel(
                handle, messageRepository, conversationRepository, attachmentRepository, session);
    }

    private static void assertEdit(
            String messageId, String original, String draft, ChatViewModel model) {
        ChatEditSession session = model.currentEditSession();
        assertNotNull(session);
        assertEquals(messageId, session.getMessageId());
        assertEquals(original, session.getOriginalContent());
        assertEquals(draft, session.getDraft().getContent());
    }

    private static ChatMessage ownText(String id, String content) {
        return new ChatMessage(
                id,
                CONVERSATION_ID,
                4L,
                "me-1",
                "Me",
                MessageDto.TYPE_TEXT,
                content,
                null,
                null,
                null,
                1_700_000_000_000L,
                null,
                DeliveryStatus.SENT,
                true,
                List.of(),
                List.of(),
                null,
                null);
    }
}
